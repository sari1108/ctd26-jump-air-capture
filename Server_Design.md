# Server Design — Scaling to Cloud Scale

## Where the current server stands today

The current server (`ServerMain` → `MatchmakingServer` → `Match`/`RoomRegistry`) is a
**single JVM process on a single machine**:

- One `ServerSocket`, one process, one port.
- User accounts + ELO live in **SQLite** (`users.db`), a single embedded file.
- The ELO matchmaking queue is a plain **in-memory `List`** inside `MatchmakingServer`.
- The room registry is a plain **in-memory `ConcurrentHashMap`** inside `RoomRegistry`.
- Every active game is a `Match` object living only in that one process's RAM, driven by
  its own thread ticking every 16ms and broadcasting a full `GAMESTATE` snapshot to both
  players **every tick** (60 times/second), plus discrete `MOVE`/`GAMEOVER`/`GAMESTARTED`
  events on top of that.

This was the right call for the assignment's actual test scale (a handful of concurrent
players on one machine). None of it survives contact with "100M registered users, 10M
concurrent" unchanged. Below is where each assumption breaks, and what replaces it.

---

## Update (day 2) — mapping onto the course's proposed architecture

The course staff shared their own reference design a day after this doc was first written.
Good news: the analysis below (written independently, from just the four scaling
questions) already lands on almost the same shape. Their proposal names six components;
here's how those map onto what's below, and what's genuinely new:

| Course's component | Maps to (this doc) | Status |
|---|---|---|
| **API Gateway** (login, rooms, history — non-realtime) | "Auth service" in Q2 | Already designed |
| **WebSocket Gateway** (live connections, state updates) | "Gateway/router" role in Q4's four-roles list | Already designed |
| **Matchmaker** (pairs players) | "Matchmaking/Room service" in Q2 | Already designed |
| **Game Allocator** (picks *which* Game Server shard runs a room) | Folded into "Matchmaking/Room service" in Q2 | The course splits this out as its own step — worth doing: picking *which* game-hosting instance is a distinct decision (load-based placement) from *pairing* two players, even if one service does both today in this doc |
| **Game Server Shards** (run the actual games, authoritative) | "Game-hosting service" in Q2/Q4 | Already designed — and already true in the current code: `GameSession` is the only thing that mutates board state; neither `NetworkGameWindow` (client) nor the WebSocket layer ever apply a move themselves, they only forward intent and render whatever the server/GameSession decided |
| **Observability** (logs, metrics, health checks, load tests) | Not previously covered | **New.** See below. |

**Technology choices**, cross-checked against the course's recommendation:

- **PostgreSQL** for persistent data (users, games, results, move history) — matches Q1's
  conclusion exactly.
- **Redis** for ephemeral state (sessions, active rooms, reconnect, matchmaking queue) —
  matches Q2's conclusion exactly.
- **NATS / Redis Pub/Sub** for *inter-service* messaging (e.g. Game Allocator telling a
  Game Server shard "you now own room X", or a shard announcing "match Y ended" back to
  the Matchmaker/Observability) — this is a layer this doc hadn't separated out: Q2's
  Redis was scoped to *shared state* (the queue, the room→instance map), not to
  service-to-service *events*. Worth keeping those two Redis roles conceptually distinct
  even if the same Redis cluster ends up hosting both in a small deployment — one is a
  data store, the other is a message bus.
- **Docker Compose** for a small local multi-container stack, **Kubernetes/K3s** for real
  scale — matches the "Docker / Kubernetes / K3s notes" section below exactly.

**Observability — what's there today and what's missing.** `ActivityLog` already gives
every server and client a timestamped, append-only log — a real (if primitive) starting
point. What's genuinely absent and would need to be added for the course's fifth
component: structured **metrics** (requests/sec, active matches, matchmaking queue depth,
p50/p95 move-latency), **health-check endpoints** (so a load balancer or Kubernetes
`readinessProbe`/`livenessProbe` can tell a healthy instance from a stuck one — see Q4),
and actual **load tests** (nothing today simulates 10M concurrent players; that has to be
built, not inferred). This is flagged as unbuilt, not glossed over.

**On authority, explicitly:** the course's note that "the client doesn't decide game
rules, and neither does the Gateway" is already how this codebase is structured, not a
change to make. `GameSnapshotCodec`/`GameEventCodec` only ever serialize what
`GameSession` already decided; a client click becomes a `sendClick`/`sendJump` *request*
that the server-side `GameSession` validates and may reject. At scale, the same rule
just needs to keep holding across the Gateway/Matchmaker/Allocator split too — none of
those three ever touch board state, they only route connections and messages to the one
Game Server shard that owns a given match.

---

## Q1 — 100M registered users: is SQLite the right DB?

**No.** Not because of row count (100M rows is not large for a real DBMS) but because of
SQLite's *concurrency model*:

- SQLite is an **embedded, single-writer** library, not a client-server database. Even in
  WAL mode, only one write transaction is in flight at a time — every login, registration,
  and ELO update after a game serializes behind that single writer.
- It has **no network protocol**. It's a file, opened by one process. There is no way for
  multiple stateless server instances (which Q2 requires) to safely share one `users.db`
  the way they'd share a real database over the network — file-level locking across
  machines is exactly what SQLite's own docs warn against.
- No built-in replication, connection pooling, or read replicas.

**Replacement:** a real client-server RDBMS — **PostgreSQL** or **MySQL** — for the
user/credentials/ELO table. The schema is small and relational (`username` PK,
`password_hash`, `salt`, `elo`), the access pattern is mostly point lookups by username
plus occasional ELO writes at the end of each match (not high-frequency writes per user —
recall from Q4 that a match only lasts 30-90s and produces exactly two ELO writes total).
That access pattern is comfortably served by Postgres/MySQL with:

- A **primary key index on username** (already the natural PK).
- **Read replicas** for the (much more frequent) login-lookup / ELO-read path.
- Optionally a **Redis cache** in front of it for hot lookups (a logged-in user's ELO,
  looked up on every matchmaking attempt) to keep the DB off the hot path entirely.

100M rows with proper indexing is a non-event for Postgres/MySQL; the actual scaling work
is making sure *many* stateless auth-service instances can all talk to it safely — which a
real client-server DB gives you for free and SQLite fundamentally cannot.

---

## Q2 — 10M concurrent players, multiple servers: routing, matchmaking, rooms

One server obviously isn't enough — `MatchmakingServer`'s in-memory queue and
`RoomRegistry`'s in-memory map only know about players connected to *that one process*.
Two players who happen to land on different instances could never be matched, and a room
created on instance A would be invisible to someone typing its code into instance B.

The fix is to split "who has memory of what" into a **shared layer** vs. **local compute**,
and separate the system into distinct service roles:

```mermaid
flowchart TB
    Client1[Client] --> LB[Load Balancer / Gateway]
    Client2[Client] --> LB
    LB --> Auth[Auth Service<br/>stateless, N replicas]
    LB --> MM[Matchmaking/Room Service<br/>stateless, N replicas]
    Auth --> DB[(Postgres/MySQL<br/>users + ELO)]
    MM --> Redis[(Redis<br/>global queue + room registry<br/>+ match-&gt;instance map)]
    MM -->|"here's your game server"| Client1
    MM -->|"here's your game server"| Client2
    Client1 -->|direct WS connection| GH1[Game-Hosting Instance #1<br/>many concurrent Matches]
    Client2 -->|direct WS connection| GH1
    Client3[Client] -->|direct WS connection| GH2[Game-Hosting Instance #2]
    GH1 -.registers active matches.-> Redis
    GH2 -.registers active matches.-> Redis
```

- **Auth service** — stateless, horizontally scaled, talks to Postgres/MySQL. Any replica
  can serve any login.
- **Matchmaking/Room service** — also stateless *itself*, but the queue and room registry
  move out of local Java memory and into a **shared, fast store: Redis**. Redis gives
  atomic operations (so two matchmaking-service replicas racing to pair the same two
  players don't double-match them) and pub/sub, and is fast enough for a queue that's
  read/written on every `PLAY`/`CREATE_ROOM`/`JOIN_ROOM`. This directly replaces today's
  `MatchmakingServer.queue` (a local `List`) and `RoomRegistry.rooms` (a local
  `ConcurrentHashMap`) with the *same data structures, conceptually*, just backed by Redis
  instead of local JVM heap — so any matchmaking-service replica can answer "does room
  ABC123 exist, and which game-hosting instance is running it?"
- **Game-hosting service** — this is today's `Match`/`GameSession` machinery, basically
  unchanged in spirit: one process still hosts *many* concurrent, independent games in
  memory, ticking them forward. It's just now one of many horizontally-scaled instances,
  and each individual match is **pinned to exactly one instance** for its whole (short)
  lifetime — no need to replicate live game state across instances, a 30-90s game is cheap
  to just lose and restart if its host dies (see Q4).
- **"Everyone can play with everyone" / "join any room"**: once the queue and room
  registry are shared (Redis) instead of per-process, this falls out naturally — a
  matchmaking-service replica can pair two players who connected to *different* replicas,
  and any replica can resolve any room code to the game-hosting instance that owns it.
- **Routing a client to its game**: when a match is created, the matchmaking service picks
  an available game-hosting instance (e.g. least-loaded, or round-robin), records
  `matchId -> instanceAddress` in Redis, and tells both clients *that specific instance's
  address* to connect to directly. This avoids trying to load-balance a long-lived
  WebSocket connection per-message (which doesn't work well) — the client makes one new
  direct connection to the instance that actually owns its match, exactly once.

---

## Q3 — Network traffic: a move every ~2s, is that a lot?

This is the most concrete, code-visible problem. **A move every 2 seconds is nothing.**
The current *implementation* is the problem, not the game's actual data rate:

- `Match.tickLoop()` broadcasts a **full `GAMESTATE` snapshot every 16ms (60Hz)** to each
  player, regardless of whether anything changed. A snapshot line-encodes every piece on
  an 8x8 board (up to ~32 pieces) plus the legal-moves list — call it roughly **1-1.5 KB**
  per message.
  - Per player: `60/sec × ~1.3 KB ≈ 78 KB/s` (~625 kbps) **even while nothing is
    happening** on the board.
  - At **10 million concurrent players**, that's on the order of **~780 GB/s** of
    aggregate outbound traffic from snapshot spam alone — an enormous, not-actually-
    necessary number.
- Compare that to what the *game* actually needs to communicate: one `MOVE` event
  (piece, from, to, capture info, timestamp — a couple hundred bytes) roughly **once every
  2 seconds** per active player, per the assignment's own stated average. That's
  `~150 B / 2s ≈ 75 B/s` per player. At 10M concurrent players: **~750 MB/s** aggregate —
  about **three orders of magnitude less** than the naive 60Hz-broadcast approach.

**What this means for the code:** the fix isn't a new piece of infrastructure, it's
changing *how the game-hosting instance talks to its own players* — move from "poll/push
full state on a fixed high-frequency timer" to **event-driven, delta-only updates**. The
codebase already has half of this: `GameEventCodec` (added this iteration) broadcasts
`MOVE`/`GAMEOVER`/`GAMESTARTED` the instant they happen, entirely independent of the tick
loop. At scale, that becomes the *primary* channel, and the 60Hz full-`GAMESTATE` push
either goes away entirely or drops to something like 1/second as a resync "keyframe" (for
a client that missed an event, e.g. after a brief reconnect) — not 60/second as a
steady-state design.

The one thing the 60Hz push currently buys is **smooth mid-move animation** (a piece
visibly sliding across the board over `MOVE_DURATION_MS`). That doesn't need server pushes
at all: the client already receives (via `MoveEvent`/`PendingMove`'s shape) *"piece X left A
at time T, arrives at B at time T+distance*1000ms"* in a single message — the client can
interpolate that animation locally over the duration, exactly the same way the server's own
`SnapshotBuilder` computes the interpolated position today, just done client-side instead
of re-sent 60 times a second.

---

## Q4 — Games last 30-90s: what does that say about Docker roles?

Short-lived matches change the shape of the game-hosting fleet specifically:

- **Many matches per instance, not one container per match.** A container's startup
  overhead would dwarf a 30-90s match if spun up per-game; instead, each game-hosting
  instance hosts *many* concurrent short matches over its lifetime — which is exactly what
  the current `Match`-per-thread-inside-one-process design already does. That part of the
  architecture scales *out* (more instances), not *up* (bigger single instance).
- **Fast, aggressive autoscaling is cheap and safe here.** Because no single match outlives
  ~90 seconds, the game-hosting fleet can scale in/out on a timescale of minutes without
  ever having to forcibly kill a long-running session — unlike a typical stateful service.
- **Graceful drain matters more than failover.** When scaling *in*, a game-hosting pod
  should stop accepting *new* matches (flip its readiness so the matchmaking service stops
  routing to it) but keep running until its *current* matches finish — bounded by the 90s
  average, so a short grace period (say 2 minutes) before hard termination is enough. This
  maps directly onto Kubernetes' `readinessProbe` + `preStop` hook +
  `terminationGracePeriodSeconds` primitives.
- **Losing an in-progress match is cheap and acceptable.** If a game-hosting pod crashes
  outright, both players lose at most ~90 seconds of a low-stakes casual game — cheap
  enough that there's no need for the complexity of replicating live `GameSession` state
  across instances. Just tell both clients "connection lost, please requeue." This is a
  deliberate simplification worth calling out, not an oversight: don't build multi-instance
  game-state replication for something this disposable.
- **This gives four distinct Docker/service roles**, each with its own scaling trigger:
  1. **Auth service** — stateless, scales on request rate, simplest of the four.
  2. **Matchmaking/Room service** — stateless compute + shared Redis state, scales on
     connection/queue-churn rate.
  3. **Game-hosting service** — scales on *concurrent match count* (not raw player count
     directly, since each match is ~2-3 players and a roughly fixed compute cost), with
     graceful-drain-aware autoscaling as above.
  4. **Gateway/router** — routes each client's game connection to the right game-hosting
     instance via the Redis-backed match registry; low-latency, thin, scales on connection
     count.

---

## Docker / Kubernetes / K3s notes

- Each of the four roles above becomes its **own Docker image** (small, single-purpose,
  independently deployable and independently scalable — the opposite of today's one
  monolithic `ServerMain`).
- **Kubernetes** is what turns "four images" into "a fleet": a `Deployment` per service
  role with its own `HorizontalPodAutoscaler` (auth/matchmaking scale on CPU or request
  rate; game-hosting scales on a custom metric like active-match count), a `Service` for
  internal discovery, and an `Ingress`/gateway for external WebSocket entry.
- **K3s** is a good fit for prototyping this locally before touching a real cloud cluster —
  a single lightweight binary that runs a real (if trimmed-down) Kubernetes control plane
  on one dev machine, so the multi-service design above can be built, deployed, and load-
  tested end-to-end without needing a managed cloud cluster yet.

---

## What actually changes in the code, concretely

| Today | At scale |
|---|---|
| `UserDatabase` → SQLite file | ✅ **Done** — Postgres via JDBC, selected by `DB_URL` (see "What we shipped today"); connection pooling across many stateless replicas is the one piece still ahead |
| `MatchmakingServer.queue` (local `List`) | Redis-backed shared queue |
| `RoomRegistry.rooms` (local `ConcurrentHashMap`) | Redis-backed shared map (`roomId -> instance`) |
| One process = auth + matchmaking + game hosting | Four separate services/images |
| `Match.tickLoop()` broadcasts full `GAMESTATE` @ 60Hz | Event-driven (`MOVE`/`GAMEOVER`/`GAMESTARTED`) as primary channel; full snapshot only as an infrequent resync keyframe |
| Client reconnects to the same single server | Matchmaking hands the client a *specific* game-hosting instance address to connect to directly |
| No graceful shutdown story | Readiness-gated, drain-aware shutdown tied to the ~90s match ceiling |

The core game engine (`GameSession`, the validators, `ArrivalResolver`,
`CollisionResolver`, the bus) doesn't need to change at all — it's already a clean,
single-match unit of compute. The scaling work is entirely about *how many of those units
run, where, and how clients find the right one* — which is exactly what Q1-Q4 are asking.

---

## What we shipped today: a small, working Docker Compose setup

Per the instructions — "prefer something small that works over trying to build
everything and having it not work" — today's deliverable is deliberately *not* a rewrite
into the six-component design above. It's the current, already-working monolithic server
(`ServerMain` → `MatchmakingServer`), containerized and proven to actually run and accept
connections through Docker, **now backed by real PostgreSQL instead of SQLite**:

- **`Dockerfile`** — compiles the existing source tree exactly the way `compile.bat`
  already does (one `javac` pass, `lib/*.jar` on the classpath). The entrypoint reads a
  `DB_URL` environment variable: if it's a `jdbc:postgresql:...` URL, the server talks to
  Postgres; if unset, it falls back to a SQLite file on a mounted volume (so the image
  still works standalone with `docker run`, no compose/Postgres required).
- **`docker-compose.yml`** — now defines **two** services: `postgres` (official
  `postgres:16-alpine` image, with a health check so `game-server` waits for it to be
  actually ready, not just started) and `game-server` (built from the `Dockerfile`,
  `DB_URL` pointed at the `postgres` service by its Compose network name).
- **`net/UserDatabase.java`** — updated to answer Q1 with running code, not just
  prose. The constructor now detects whether it was given a plain file path (SQLite) or a
  `jdbc:postgresql:` URL (Postgres) and loads the matching JDBC driver
  (`lib/postgresql.jar`, added alongside `lib/sqlite-jdbc.jar`). The schema and every
  query were already portable standard SQL (`TEXT`/`INTEGER` columns, no SQLite-specific
  syntax) — so this was a real driver/connection-string switch, not a rewrite, and the
  **local/native SQLite path (`2-Start-Server.bat`) is completely unchanged and still
  works exactly as before.**
- **`.dockerignore`** — keeps `upload/`, `out/`, logs, and the native `users.db` out of
  the build context.

**Verified today, not just written:**
- `docker compose build` compiles cleanly inside the container.
- The `postgres` container reports healthy, and `game-server` waits for that before
  starting (`depends_on: condition: service_healthy`).
- With `game-server` pointed at the real Postgres container, `psql` confirms the `users`
  table was created there with the correct schema.
- A live `UserDatabase` round-trip against that same Postgres instance — register, login
  with the correct password, login with the wrong password, read ELO, update ELO, read it
  back — produced the correct result at every step, and `SELECT * FROM users` afterward
  showed the row with the updated ELO, actually persisted in Postgres.
- Separately, the **native SQLite path was re-verified unaffected**: the whole project
  still compiles with `javac -cp 'lib\*'` (now including `postgresql.jar` on the
  classpath with zero conflicts), and the same register/login/ELO round-trip against a
  throwaway SQLite file produced identical results.

**To run it:** `docker compose up --build` from the project root (this now also starts a
real Postgres, no separate setup needed), then connect a client (`3-Play-Online.bat`) to
`localhost:5000` exactly as with the native server.

**Deliberately not done today** (next step, not an oversight): splitting this one
container into the four-to-six *services* from the table above (separate Auth /
Matchmaking / Game-hosting processes), and moving the matchmaking queue + room registry
off local Java memory and onto Redis. Today's change closes the Q1 gap (SQLite → a real
client-server DB); Q2's gap (one process, one instance) is still open — that's the next
milestone, and it's a bigger one: a new inter-service protocol, a Redis-backed queue
replacing the in-memory `List`, and doing it without breaking the one thing that
currently works isn't a same-day change.
