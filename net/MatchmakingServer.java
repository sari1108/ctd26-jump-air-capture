import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

// Top-level server: accepts WebSocket connections, logs each one in against
// the SQLite-backed UserDatabase, then holds it in a lobby until it sends
// "PLAY". A background matchmaker pairs up any two waiting players whose ELO
// is within 100 of each other, or tells a player after a minute of waiting
// that none was found (per the course's Home-screen "Play" button spec).
// Each pairing gets its own fresh GameSession, run by its own Match - so
// multiple games can be in progress on this one server at once.
//
// The waiting queue lives in one of two places depending on whether a RedisClient
// was supplied: with no Redis (native/local runs), it's a plain in-memory List, exactly
// as before. With Redis (the Docker Compose stack), the queue's *data* - who's waiting,
// their ELO, when they queued - is mirrored into Redis (a real shared store, inspectable
// with redis-cli), which is what a second server instance would need to see the same
// queue. The live WebSocketConnection itself can never leave this process either way -
// that part stays in a local map, keyed by the same id used in Redis.
public class MatchmakingServer {
    private static final long ELO_RANGE = 100;
    private static final long SEARCH_TIMEOUT_MS = 60_000;
    private static final long MATCHMAKER_TICK_MS = 500;
    private static final String REDIS_QUEUE_KEY = "mm:queue";

    private final int port;
    private final UserDatabase userDatabase;
    private final ActivityLog log;
    private final Supplier<GameSession> sessionFactory;
    private final RoomRegistry roomRegistry;
    private final GameAllocator gameAllocator;
    private final RedisClient redis;
    private final List<QueueEntry> queue = new ArrayList<>();
    private final Object queueLock = new Object();
    private final Map<String, QueueEntry> entriesById = new ConcurrentHashMap<>();
    private final AtomicLong matchesStarted = new AtomicLong();
    private volatile boolean running = false;

    // Observability (see Server_Design.md / HealthServer): cheap, lock-free snapshots,
    // fine for a diagnostics endpoint that doesn't need a perfectly consistent read.
    int queueDepth() {
        return redis != null ? entriesById.size() : queue.size();
    }

    long matchesStartedCount() {
        return matchesStarted.get();
    }

    int activeRoomCount() {
        return roomRegistry.roomCount();
    }

    public MatchmakingServer(int port, UserDatabase userDatabase, ActivityLog log, Supplier<GameSession> sessionFactory) {
        this(port, userDatabase, log, sessionFactory, null);
    }

    public MatchmakingServer(int port, UserDatabase userDatabase, ActivityLog log,
                              Supplier<GameSession> sessionFactory, RedisClient redis) {
        this.port = port;
        this.userDatabase = userDatabase;
        this.log = log;
        this.sessionFactory = sessionFactory;
        this.redis = redis;
        this.gameAllocator = new GameAllocator(userDatabase, log);
        this.roomRegistry = new RoomRegistry(userDatabase, log, sessionFactory, redis, gameAllocator);
    }

    // Found empirically, not assumed: a load test connecting 200 simulated players in a
    // tight burst dropped ~15 of them with Java's default accept backlog (~50). Explicit
    // backlog of 1024 fixed it - see Server_Design.md's load-test results for numbers
    // before and after.
    private static final int ACCEPT_BACKLOG = 1024;

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(port, ACCEPT_BACKLOG);
        running = true;
        log.log("MatchmakingServer listening on port " + port);

        Thread accepter = new Thread(() -> acceptLoop(serverSocket), "matchmaking-accept");
        accepter.setDaemon(true);
        accepter.start();

        Thread matchmaker = new Thread(this::matchmakerLoop, "matchmaker");
        matchmaker.setDaemon(true);
        matchmaker.start();
    }

    private void acceptLoop(ServerSocket serverSocket) {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                log.log("Connection accepted from " + socket.getRemoteSocketAddress());
                WebSocketConnection connection = WebSocketHandshake.serverHandshake(socket);
                Thread worker = new Thread(() -> loginThenLobby(connection), "login-lobby");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (running) log.log("Accept loop error: " + e);
            }
        }
    }

    private void loginThenLobby(WebSocketConnection connection) {
        String username;
        int elo;
        try {
            String message = connection.receiveText();
            if (message == null || !message.startsWith("LOGIN ")) {
                log.log("Login rejected: protocol_error (message=" + message + ")");
                connection.sendText("LOGIN_FAIL protocol_error");
                connection.close();
                return;
            }
            String[] parts = message.split("\\s+", 3);
            if (parts.length < 3) {
                log.log("Login rejected: protocol_error (malformed LOGIN)");
                connection.sendText("LOGIN_FAIL protocol_error");
                connection.close();
                return;
            }
            username = parts[1];
            String password = parts[2];

            UserDatabase.LoginResult result;
            try {
                result = userDatabase.login(username, password);
            } catch (SQLException e) {
                log.log("Login failed for " + username + ": server_error (" + e.getMessage() + ")");
                connection.sendText("LOGIN_FAIL server_error");
                connection.close();
                return;
            }
            if (result == UserDatabase.LoginResult.WRONG_PASSWORD) {
                log.log("Login rejected for " + username + ": wrong_password");
                connection.sendText("LOGIN_FAIL wrong_password");
                connection.close();
                return;
            }

            try {
                elo = userDatabase.getElo(username);
            } catch (SQLException e) {
                log.log("Login failed for " + username + ": server_error (" + e.getMessage() + ")");
                connection.sendText("LOGIN_FAIL server_error");
                connection.close();
                return;
            }

            connection.sendText("LOGIN_OK " + elo);
            log.log(username + " logged in (" + result + ", elo " + elo + ")");
        } catch (IOException e) {
            return;
        }

        try {
            lobbyLoop(connection, username, elo);
        } catch (IOException e) {
            log.log(username + " disconnected from lobby: " + e.getMessage());
        }
    }

    private void lobbyLoop(WebSocketConnection connection, String username, int elo) throws IOException {
        while (true) {
            String cmd = connection.receiveText();
            if (cmd == null) return;
            String trimmed = cmd.trim();

            if ("PLAY".equals(trimmed)) {
                connection.sendText("SEARCHING");
                log.log(username + " joined the matchmaking queue (elo " + elo + ")");
                QueueEntry entry = new QueueEntry(connection, username, elo);
                addToWaiting(entry);

                try {
                    entry.resolved.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (!entry.timedOut) {
                    return; // matched - the new Match now owns reading from this connection
                }
                // timed out with no match: loop back and wait for this player to hit Play again
            } else if ("CREATE_ROOM".equals(trimmed)) {
                roomRegistry.create(connection, username, elo);
                return; // matched - the new Match now owns reading from this connection
            } else if (trimmed.startsWith("JOIN_ROOM ")) {
                String roomId = trimmed.substring("JOIN_ROOM ".length()).trim();
                if (roomRegistry.join(roomId, connection, username, elo)) {
                    return; // ownership handed off (became Black, or attached as a spectator)
                }
                // room code not found: loop back, stay in the lobby
            }
        }
    }

    private void matchmakerLoop() {
        while (running) {
            try {
                Thread.sleep(MATCHMAKER_TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            long now = System.currentTimeMillis();
            List<QueueEntry> waiting = redis != null ? snapshotFromRedis() : snapshotFromLocalQueue();
            PairingResult result = computePairing(waiting, now);

            for (QueueEntry entry : result.timedOut) {
                removeFromWaiting(entry);
            }
            for (QueueEntry[] pair : result.pairs) {
                removeFromWaiting(pair[0]);
                removeFromWaiting(pair[1]);
            }

            for (QueueEntry entry : result.timedOut) {
                log.log(entry.username + " gave up waiting: no opponent found within " + (SEARCH_TIMEOUT_MS / 1000) + "s");
                sendSafely(entry.connection, "NO_MATCH");
                entry.timedOut = true;
                entry.resolved.countDown();
            }
            for (QueueEntry[] pair : result.pairs) {
                startMatch(pair[0], pair[1]);
            }
        }
    }

    // Same ELO-window greedy pairing + timeout logic regardless of where the entries
    // came from (local list or Redis) - this is the one piece of matchmaking behavior
    // that must stay identical between the two backing stores.
    private static PairingResult computePairing(List<QueueEntry> entries, long now) {
        List<QueueEntry[]> pairs = new ArrayList<>();
        boolean[] taken = new boolean[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            if (taken[i]) continue;
            for (int j = i + 1; j < entries.size(); j++) {
                if (taken[j]) continue;
                if (Math.abs(entries.get(i).elo - entries.get(j).elo) <= ELO_RANGE) {
                    taken[i] = true;
                    taken[j] = true;
                    pairs.add(new QueueEntry[]{entries.get(i), entries.get(j)});
                    break;
                }
            }
        }
        List<QueueEntry> timedOut = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (taken[i]) continue;
            QueueEntry entry = entries.get(i);
            if (now - entry.queuedAt >= SEARCH_TIMEOUT_MS) {
                timedOut.add(entry);
            }
        }
        return new PairingResult(pairs, timedOut);
    }

    private void addToWaiting(QueueEntry entry) {
        if (redis != null) {
            entriesById.put(entry.id, entry);
            try {
                String key = redisEntryKey(entry.id);
                redis.hset(key, "username", entry.username);
                redis.hset(key, "elo", String.valueOf(entry.elo));
                redis.hset(key, "queuedAt", String.valueOf(entry.queuedAt));
                redis.sadd(REDIS_QUEUE_KEY, entry.id);
            } catch (IOException e) {
                log.log("Redis error adding " + entry.username + " to queue: " + e.getMessage());
            }
        } else {
            synchronized (queueLock) {
                queue.add(entry);
            }
        }
    }

    private void removeFromWaiting(QueueEntry entry) {
        if (redis != null) {
            entriesById.remove(entry.id);
            try {
                redis.srem(REDIS_QUEUE_KEY, entry.id);
                redis.del(redisEntryKey(entry.id));
            } catch (IOException e) {
                log.log("Redis error removing " + entry.username + " from queue: " + e.getMessage());
            }
        } else {
            synchronized (queueLock) {
                queue.remove(entry);
            }
        }
    }

    private List<QueueEntry> snapshotFromLocalQueue() {
        synchronized (queueLock) {
            return new ArrayList<>(queue);
        }
    }

    private List<QueueEntry> snapshotFromRedis() {
        List<QueueEntry> snapshot = new ArrayList<>();
        try {
            Set<String> ids = redis.smembers(REDIS_QUEUE_KEY);
            for (String id : ids) {
                QueueEntry entry = entriesById.get(id);
                if (entry != null) snapshot.add(entry); // null = entry from a prior server run; ignore
            }
        } catch (IOException e) {
            log.log("Redis error reading queue: " + e.getMessage());
        }
        return snapshot;
    }

    private static String redisEntryKey(String id) {
        return "mm:entry:" + id;
    }

    private static final class PairingResult {
        final List<QueueEntry[]> pairs;
        final List<QueueEntry> timedOut;

        PairingResult(List<QueueEntry[]> pairs, List<QueueEntry> timedOut) {
            this.pairs = pairs;
            this.timedOut = timedOut;
        }
    }

    private void startMatch(QueueEntry a, QueueEntry b) {
        Match.Seat white = new Match.Seat(a.connection, a.username, "WHITE");
        Match.Seat black = new Match.Seat(b.connection, b.username, "BLACK");

        sendSafely(a.connection, "MATCH_FOUND WHITE " + b.username + " " + b.elo);
        sendSafely(b.connection, "MATCH_FOUND BLACK " + a.username + " " + a.elo);

        gameAllocator.allocate(sessionFactory, white, black);
        matchesStarted.incrementAndGet();
        log.log("Match started: " + a.username + " (white, elo " + a.elo + ") vs "
                + b.username + " (black, elo " + b.elo + ")");

        a.resolved.countDown();
        b.resolved.countDown();
    }

    private void sendSafely(WebSocketConnection connection, String text) {
        try {
            connection.sendText(text);
        } catch (IOException ignored) {
        }
    }

    public void stop() {
        running = false;
    }

    private static final class QueueEntry {
        final String id;
        final WebSocketConnection connection;
        final String username;
        final int elo;
        final long queuedAt;
        final CountDownLatch resolved = new CountDownLatch(1);
        volatile boolean timedOut = false;

        QueueEntry(WebSocketConnection connection, String username, int elo) {
            this.id = UUID.randomUUID().toString();
            this.connection = connection;
            this.username = username;
            this.elo = elo;
            this.queuedAt = System.currentTimeMillis();
        }
    }
}
