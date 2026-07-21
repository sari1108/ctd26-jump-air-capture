import java.io.IOException;
import java.sql.SQLException;

// One active game between two already-matched, already-seated players. Owns
// its own fresh GameSession (so multiple matches can run at once on the same
// server), ticks it forward and broadcasts snapshots exactly like the old
// single-game GameServer did, but adds the piece two things a real
// matchmaking system needs on top: per-color move ownership, and a 20s
// disconnect grace period after which the still-connected player wins.
public class Match {
    private static final long TICK_MS = 16;
    private static final long DISCONNECT_GRACE_MS = 20_000;

    private final GameSession session;
    private final UserDatabase userDatabase;
    private final ActivityLog log;
    private final Seat white;
    private final Seat black;
    private final Object lock = new Object();
    private volatile boolean running = true;

    public Match(GameSession session, UserDatabase userDatabase, ActivityLog log, Seat white, Seat black) {
        this.session = session;
        this.userDatabase = userDatabase;
        this.log = log;
        this.white = white;
        this.black = black;
    }

    public void start() {
        session.getBus().subscribe(GameSession.TOPIC_GAME_OVER, payload -> onGameOver((GameOverEvent) payload));

        // Broadcast each bus event the instant it happens (not just baked into the next
        // periodic GAMESTATE tick), so clients can drive score/move-log/sound off the
        // same bus events local play uses - see GameEventCodec.
        session.getBus().subscribe(GameSession.TOPIC_MOVE_RESOLVED,
                payload -> broadcast(GameEventCodec.encodeMove((MoveEvent) payload)));
        session.getBus().subscribe(GameSession.TOPIC_GAME_OVER,
                payload -> broadcast(GameEventCodec.encodeGameOver((GameOverEvent) payload)));
        session.getBus().subscribe(GameSession.TOPIC_GAME_STARTED,
                payload -> broadcast(GameEventCodec.encodeGameStarted((GameStartedEvent) payload)));

        broadcast("NAMES " + white.username + " " + black.username);

        startReader(white, black);
        startReader(black, white);

        Thread ticker = new Thread(this::tickLoop, "match-tick");
        ticker.setDaemon(true);
        ticker.start();

        synchronized (lock) {
            session.start();
        }
    }

    private void startReader(Seat self, Seat opponent) {
        Thread reader = new Thread(() -> {
            try {
                String cmd;
                while ((cmd = self.connection.receiveText()) != null) {
                    handleCommand(cmd, self);
                }
                handleDisconnect(self, opponent);
            } catch (IOException e) {
                handleDisconnect(self, opponent);
            }
        }, "match-read-" + self.color);
        reader.setDaemon(true);
        reader.start();
    }

    private void handleDisconnect(Seat self, Seat opponent) {
        if (!running || !self.connected) return;
        self.connected = false;
        log.log(self.username + " disconnected mid-game; " + opponent.username + " wins in "
                + (DISCONNECT_GRACE_MS / 1000) + "s unless they return.");

        Thread grace = new Thread(() -> {
            for (long remaining = DISCONNECT_GRACE_MS; remaining > 0; remaining -= 1000) {
                if (!running) return;
                sendTo(opponent, "OPPONENT_DISCONNECTED " + (remaining / 1000));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            synchronized (lock) {
                if (running && !session.snapshot().isGameOver()) {
                    session.forceGameOver(opponent.color);
                }
            }
        }, "match-disconnect-grace");
        grace.setDaemon(true);
        grace.start();
    }

    // Standard ELO update (K=32) once this match's game actually ends, win or
    // resign. The tick loop is deliberately left running afterwards (same as
    // local play) so the game-over fade-in animation still has a clock.
    private void onGameOver(GameOverEvent event) {
        try {
            int whiteElo = userDatabase.getElo(white.username);
            int blackElo = userDatabase.getElo(black.username);

            double expectedWhite = 1.0 / (1.0 + Math.pow(10, (blackElo - whiteElo) / 400.0));
            double expectedBlack = 1.0 - expectedWhite;
            double scoreWhite = "WHITE".equals(event.winnerColor) ? 1.0 : 0.0;
            double scoreBlack = 1.0 - scoreWhite;

            int K = 32;
            int newWhiteElo = (int) Math.round(whiteElo + K * (scoreWhite - expectedWhite));
            int newBlackElo = (int) Math.round(blackElo + K * (scoreBlack - expectedBlack));

            userDatabase.updateElo(white.username, newWhiteElo);
            userDatabase.updateElo(black.username, newBlackElo);

            log.log("Match over, winner=" + event.winnerColor
                    + ". ELO: " + white.username + " " + whiteElo + "->" + newWhiteElo
                    + ", " + black.username + " " + blackElo + "->" + newBlackElo);
        } catch (SQLException e) {
            log.log("Failed to update ELO: " + e.getMessage());
        }
    }

    // A seat may only start a selection on a piece of its own color - enforced
    // here, server-side, not just by the client's own UI.
    private void handleCommand(String message, Seat seat) {
        String[] parts = message.trim().split("\\s+");
        if (parts.length == 0) return;

        synchronized (lock) {
            switch (parts[0]) {
                case "CLICK": {
                    int row = Integer.parseInt(parts[1]);
                    int col = Integer.parseInt(parts[2]);
                    PieceColor color = PieceColor.valueOf(seat.color);
                    if (session.getSelectedPosition(color) == null && !ownsPieceAt(seat, row, col)) return;
                    session.selectOrMove(color, row, col);
                    break;
                }
                case "JUMP": {
                    int row = Integer.parseInt(parts[1]);
                    int col = Integer.parseInt(parts[2]);
                    if (!ownsPieceAt(seat, row, col)) return;
                    session.activateJump(row, col);
                    break;
                }
                case "DESELECT":
                    session.deselect(PieceColor.valueOf(seat.color));
                    break;
                default:
                    log.log("Unknown in-match command from " + seat.username + ": " + message);
            }
        }
    }

    private boolean ownsPieceAt(Seat seat, int row, int col) {
        Piece piece = session.getBoard().getPiece(row, col);
        return piece != null && !piece.isEmpty() && piece.getColor().name().equals(seat.color);
    }

    private void tickLoop() {
        long lastTick = System.currentTimeMillis();
        while (running) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastTick;
            lastTick = now;

            // Personalized per seat: board/pending-moves/game-over state is identical for
            // both, but each seat's "selected square" and legal-move highlights are its
            // own (see GameSession.snapshot(PieceColor)) - never the other seat's.
            String encodedWhite, encodedBlack;
            synchronized (lock) {
                session.waitMs(elapsed);
                encodedWhite = GameSnapshotCodec.encode(session.snapshot(PieceColor.WHITE));
                encodedBlack = GameSnapshotCodec.encode(session.snapshot(PieceColor.BLACK));
            }
            sendTo(white, encodedWhite);
            sendTo(black, encodedBlack);

            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void broadcast(String text) {
        sendTo(white, text);
        sendTo(black, text);
    }

    private void sendTo(Seat seat, String text) {
        if (!seat.connected) return;
        try {
            seat.connection.sendText(text);
        } catch (IOException ignored) {
            // the reader thread for this seat will notice the same failure and run handleDisconnect
        }
    }

    public static final class Seat {
        final WebSocketConnection connection;
        final String username;
        final String color;
        volatile boolean connected = true;

        public Seat(WebSocketConnection connection, String username, String color) {
            this.connection = connection;
            this.username = username;
            this.color = color;
        }
    }
}
