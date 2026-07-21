import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

// Top-level server: accepts WebSocket connections, logs each one in against
// the SQLite-backed UserDatabase, then holds it in a lobby until it sends
// "PLAY". A background matchmaker pairs up any two waiting players whose ELO
// is within 100 of each other, or tells a player after a minute of waiting
// that none was found (per the course's Home-screen "Play" button spec).
// Each pairing gets its own fresh GameSession, run by its own Match - so
// multiple games can be in progress on this one server at once.
public class MatchmakingServer {
    private static final long ELO_RANGE = 100;
    private static final long SEARCH_TIMEOUT_MS = 60_000;
    private static final long MATCHMAKER_TICK_MS = 500;

    private final int port;
    private final UserDatabase userDatabase;
    private final ActivityLog log;
    private final Supplier<GameSession> sessionFactory;
    private final List<QueueEntry> queue = new ArrayList<>();
    private final Object queueLock = new Object();
    private volatile boolean running = false;

    public MatchmakingServer(int port, UserDatabase userDatabase, ActivityLog log, Supplier<GameSession> sessionFactory) {
        this.port = port;
        this.userDatabase = userDatabase;
        this.log = log;
        this.sessionFactory = sessionFactory;
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
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
            if (!"PLAY".equals(cmd.trim())) continue;

            connection.sendText("SEARCHING");
            log.log(username + " joined the matchmaking queue (elo " + elo + ")");
            QueueEntry entry = new QueueEntry(connection, username, elo);
            synchronized (queueLock) {
                queue.add(entry);
            }

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

            List<QueueEntry[]> pairsToStart = new ArrayList<>();
            List<QueueEntry> timedOut = new ArrayList<>();
            long now = System.currentTimeMillis();

            synchronized (queueLock) {
                boolean[] taken = new boolean[queue.size()];
                for (int i = 0; i < queue.size(); i++) {
                    if (taken[i]) continue;
                    for (int j = i + 1; j < queue.size(); j++) {
                        if (taken[j]) continue;
                        if (Math.abs(queue.get(i).elo - queue.get(j).elo) <= ELO_RANGE) {
                            taken[i] = true;
                            taken[j] = true;
                            pairsToStart.add(new QueueEntry[]{queue.get(i), queue.get(j)});
                            break;
                        }
                    }
                }
                List<QueueEntry> remaining = new ArrayList<>();
                for (QueueEntry entry : queue) {
                    boolean matched = pairsToStart.stream().anyMatch(pair -> pair[0] == entry || pair[1] == entry);
                    if (matched) continue;
                    if (now - entry.queuedAt >= SEARCH_TIMEOUT_MS) {
                        timedOut.add(entry);
                    } else {
                        remaining.add(entry);
                    }
                }
                queue.clear();
                queue.addAll(remaining);
            }

            for (QueueEntry entry : timedOut) {
                log.log(entry.username + " gave up waiting: no opponent found within " + (SEARCH_TIMEOUT_MS / 1000) + "s");
                sendSafely(entry.connection, "NO_MATCH");
                entry.timedOut = true;
                entry.resolved.countDown();
            }
            for (QueueEntry[] pair : pairsToStart) {
                startMatch(pair[0], pair[1]);
            }
        }
    }

    private void startMatch(QueueEntry a, QueueEntry b) {
        Match.Seat white = new Match.Seat(a.connection, a.username, "WHITE");
        Match.Seat black = new Match.Seat(b.connection, b.username, "BLACK");

        sendSafely(a.connection, "MATCH_FOUND WHITE " + b.username + " " + b.elo);
        sendSafely(b.connection, "MATCH_FOUND BLACK " + a.username + " " + a.elo);

        Match match = new Match(sessionFactory.get(), userDatabase, log, white, black);
        match.start();
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
        final WebSocketConnection connection;
        final String username;
        final int elo;
        final long queuedAt;
        final CountDownLatch resolved = new CountDownLatch(1);
        volatile boolean timedOut = false;

        QueueEntry(WebSocketConnection connection, String username, int elo) {
            this.connection = connection;
            this.username = username;
            this.elo = elo;
            this.queuedAt = System.currentTimeMillis();
        }
    }
}
