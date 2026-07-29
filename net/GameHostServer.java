import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

// A real, separate Game Server Shard (course diagram component #5): a standalone process
// that only *hosts already-allocated matches* - it never accepts LOGIN, never runs
// matchmaking. A Gateway process (MatchmakingServer, in "remote" GameAllocator mode)
// decides two players should play, PUBLISHes an ALLOCATE message to this instance's own
// Redis pub/sub channel (its address is its channel name), and this instance waits for
// both players to show up and RESUME with the tokens it was told to expect.
//
// This is the actual process-level split Server_Design.md flagged as the one item too
// big to attempt alongside everything else - built now as its own opt-in deployment mode
// (GameAllocator's existing local/in-process behavior is the default and is completely
// unchanged; nothing here runs unless a GameAllocator is explicitly configured with
// remote instance addresses). GameSession/ArrivalResolver/CollisionResolver/Match don't
// change at all - the whole point of the GameAllocator seam built earlier was that the
// actual game engine never needed to know or care where matches run.
public class GameHostServer {
    private final int port;
    private final UserDatabase userDatabase;
    private final ActivityLog log;
    // Two separate connections, on purpose: once a Redis connection issues SUBSCRIBE, the
    // protocol says it's *only* for receiving pushed messages from then on - it can't also
    // serve ordinary commands. Matches were hanging (a real deadlock, found with jstack:
    // one thread blocked inside RedisClient.hset() holding the connection's monitor,
    // waiting on a socket read that only the subscribe loop's reader was ever going to
    // consume) because both were sharing the one connection. `redis` is for ordinary
    // commands (handed to each Match for its session-mirroring); `subscribeConnection` is
    // dedicated to this instance's own inbound ALLOCATE channel and touches nothing else.
    private final RedisClient redis;
    private final RedisClient subscribeConnection;
    private final String selfAddress;
    private final Supplier<GameSession> sessionFactory;
    private final Map<String, PendingMatch> matchesById = new ConcurrentHashMap<>();
    private final Map<String, TokenRef> matchIdByToken = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public GameHostServer(int port, UserDatabase userDatabase, ActivityLog log,
                           RedisClient redis, RedisClient subscribeConnection,
                           String selfAddress, Supplier<GameSession> sessionFactory) {
        this.port = port;
        this.userDatabase = userDatabase;
        this.log = log;
        this.redis = redis;
        this.subscribeConnection = subscribeConnection;
        this.selfAddress = selfAddress;
        this.sessionFactory = sessionFactory;
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(port, 1024);
        running = true;
        log.log("GameHostServer listening on port " + port + " as \"" + selfAddress + "\"");

        Thread accepter = new Thread(() -> acceptLoop(serverSocket), "gamehost-accept");
        accepter.setDaemon(true);
        accepter.start();

        Thread subscriber = new Thread(() -> {
            try {
                subscribeConnection.subscribeLoop("gamehost:" + selfAddress, this::onAllocateMessage);
            } catch (IOException e) {
                log.log("GameHostServer subscribe loop error: " + e.getMessage());
            }
        }, "gamehost-subscribe");
        subscriber.setDaemon(true);
        subscriber.start();
    }

    // "ALLOCATE <matchId> <whiteToken> <whiteUsername> <whiteElo> <blackToken> <blackUsername> <blackElo>"
    //             p[1]        p[2]          p[3]          p[4]        p[5]          p[6]          p[7]
    private void onAllocateMessage(String message) {
        String[] p = message.trim().split("\\s+");
        if (p.length < 8 || !"ALLOCATE".equals(p[0])) return;
        String matchId = p[1];
        PendingMatch pending = new PendingMatch(matchId, p[3], Integer.parseInt(p[4]), p[6], Integer.parseInt(p[7]));
        matchesById.put(matchId, pending);
        matchIdByToken.put(p[2], new TokenRef(matchId, "WHITE"));
        matchIdByToken.put(p[5], new TokenRef(matchId, "BLACK"));
        log.log("Allocated match " + matchId + ": " + p[3] + " (white) vs " + p[6] + " (black), awaiting RESUME");
    }

    private void acceptLoop(ServerSocket serverSocket) {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                WebSocketConnection connection = WebSocketHandshake.serverHandshake(socket);
                Thread worker = new Thread(() -> handleResume(connection), "gamehost-resume");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (running) log.log("GameHostServer accept loop error: " + e);
            }
        }
    }

    private void handleResume(WebSocketConnection connection) {
        try {
            String message = connection.receiveText();
            if (message == null || !message.startsWith("RESUME ")) {
                connection.sendText("RESUME_FAIL protocol_error");
                connection.close();
                return;
            }
            String token = message.substring("RESUME ".length()).trim();
            TokenRef ref = matchIdByToken.remove(token);
            if (ref == null) {
                connection.sendText("RESUME_FAIL unknown_token");
                connection.close();
                return;
            }
            PendingMatch pending = matchesById.get(ref.matchId);
            if (pending == null) {
                connection.sendText("RESUME_FAIL unknown_match");
                connection.close();
                return;
            }

            Match.Seat readySeat;
            Match.Seat opponentReadySeat = null;
            synchronized (pending) {
                connection.sendText("RESUME_OK");
                if ("WHITE".equals(ref.color)) {
                    pending.whiteConnection = connection;
                } else {
                    pending.blackConnection = connection;
                }
                if (pending.whiteConnection != null && pending.blackConnection != null && !pending.started) {
                    pending.started = true;
                    readySeat = new Match.Seat(pending.whiteConnection, pending.whiteUsername, "WHITE");
                    opponentReadySeat = new Match.Seat(pending.blackConnection, pending.blackUsername, "BLACK");
                } else {
                    readySeat = null;
                }
            }

            if (readySeat != null) {
                Match match = new Match(sessionFactory.get(), userDatabase, log, readySeat, opponentReadySeat, redis);
                match.start();
                matchesById.remove(ref.matchId);
                log.log("Match " + ref.matchId + " started on this host: "
                        + pending.whiteUsername + " (white) vs " + pending.blackUsername + " (black)");
            }
        } catch (IOException e) {
            log.log("GameHostServer resume error: " + e.getMessage());
        }
    }

    private static final class TokenRef {
        final String matchId;
        final String color;

        TokenRef(String matchId, String color) {
            this.matchId = matchId;
            this.color = color;
        }
    }

    private static final class PendingMatch {
        final String matchId;
        final String whiteUsername;
        final int whiteElo;
        final String blackUsername;
        int blackElo;
        volatile WebSocketConnection whiteConnection;
        volatile WebSocketConnection blackConnection;
        boolean started = false;

        PendingMatch(String matchId, String whiteUsername, int whiteElo, String blackUsername, int blackElo) {
            this.matchId = matchId;
            this.whiteUsername = whiteUsername;
            this.whiteElo = whiteElo;
            this.blackUsername = blackUsername;
            this.blackElo = blackElo;
        }
    }

    static String newToken() {
        return UUID.randomUUID().toString();
    }
}
