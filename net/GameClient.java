import java.io.IOException;
import java.net.Socket;

// Client side of the connection to MatchmakingServer. Logs in (username+
// password) as a blocking exchange right after the WebSocket handshake -
// only once that succeeds does the async reader thread start, dispatching
// every incoming message to the listener and forwarding PLAY/CLICK/JUMP/
// DESELECT commands the other way.
public class GameClient implements AutoCloseable {
    private volatile WebSocketConnection connection;
    private final ActivityLog log;
    private final int elo;
    private volatile boolean running = true;

    public GameClient(String host, int port, String username, String password,
                       ActivityLog log, GameClientListener listener) throws IOException {
        this.log = log;
        Socket socket = new Socket(host, port);
        // Nagle's algorithm buffers small writes hoping to coalesce them - exactly wrong
        // for a real-time game sending small, frequent updates. Without this, each move/
        // snapshot can sit queued for tens of milliseconds before actually going out.
        socket.setTcpNoDelay(true);
        connection = WebSocketHandshake.clientHandshake(socket, host, port, "/");
        log.log("Connected to " + host + ":" + port);

        connection.sendText("LOGIN " + username + " " + password);
        String reply = connection.receiveText();
        if (reply == null || !reply.startsWith("LOGIN_OK")) {
            String reason = reply == null ? "no_response" : reply;
            log.log("Login failed for " + username + ": " + reason);
            connection.close();
            throw new IOException("Login failed: " + reason);
        }
        this.elo = parseElo(reply);
        log.log("Logged in as " + username + " (elo " + elo + ")");

        Thread reader = new Thread(() -> readLoop(listener), "game-client-read");
        reader.setDaemon(true);
        reader.start();
    }

    public int getElo() {
        return elo;
    }

    private static int parseElo(String loginOkReply) throws IOException {
        String[] parts = loginOkReply.split("\\s+");
        if (parts.length < 2) {
            throw new IOException("Malformed LOGIN_OK reply: " + loginOkReply);
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed LOGIN_OK reply: " + loginOkReply);
        }
    }

    private void readLoop(GameClientListener listener) {
        try {
            String message;
            while (running && (message = connection.receiveText()) != null) {
                try {
                    dispatch(message, listener);
                } catch (RuntimeException e) {
                    // A single malformed/unexpected server message (bad GAMESTATE encoding,
                    // a command missing an argument, ...) shouldn't take down the reader
                    // thread - log it and keep listening for the next one.
                    log.log("Ignoring malformed server message '" + message + "': " + e);
                }
            }
        } catch (IOException e) {
            if (running) log.log("Disconnected from server: " + e.getMessage());
        }
    }

    private void dispatch(String message, GameClientListener listener) {
        if (message.startsWith("GAMESTATE")) {
            listener.onSnapshot(GameSnapshotCodec.decode(message));
        } else if (message.startsWith("NAMES")) {
            String[] parts = message.split("\\s+");
            listener.onNames(parts[1], parts[2]);
        } else if (message.startsWith("STATUS")) {
            listener.onStatus(message.split("\\s+")[1]);
        } else if (message.equals("SEARCHING")) {
            listener.onSearching();
        } else if (message.startsWith("MATCH_FOUND")) {
            String[] parts = message.split("\\s+");
            String color = parts[1];
            String opponent = parts[2];
            int opponentElo = Integer.parseInt(parts[3]);
            // A real process-level split (see MatchmakingServer/GameAllocator/
            // GameHostServer): 3 extra fields mean this match runs on a *different*
            // process, and the redirect must happen before the listener is told
            // anything - onMatchFound implies "the board is about to work."
            if (parts.length >= 7) {
                if (!reconnectTo(parts[4], Integer.parseInt(parts[5]), parts[6])) {
                    log.log("Redirect to " + parts[4] + ":" + parts[5] + " failed - dropping this match.");
                    listener.onRedirectFailed(parts[4] + ":" + parts[5]);
                    return;
                }
            }
            listener.onMatchFound(color, opponent, opponentElo);
        } else if (message.equals("NO_MATCH")) {
            listener.onNoMatch();
        } else if (message.startsWith("OPPONENT_DISCONNECTED")) {
            listener.onOpponentDisconnected(Integer.parseInt(message.split("\\s+")[1]));
        } else if (message.startsWith("MOVE ")) {
            listener.onMoveResolved(GameEventCodec.decodeMove(message));
        } else if (message.startsWith("GAMEOVER ")) {
            listener.onGameOver(GameEventCodec.decodeGameOver(message));
        } else if (message.startsWith("GAMESTARTED ")) {
            listener.onGameStarted(GameEventCodec.decodeGameStarted(message));
        } else if (message.startsWith("ROOM_CREATED ")) {
            listener.onRoomCreated(message.substring("ROOM_CREATED ".length()).trim());
        } else if (message.startsWith("ROOM_NOT_FOUND ")) {
            listener.onRoomNotFound(message.substring("ROOM_NOT_FOUND ".length()).trim());
        } else if (message.startsWith("SPECTATE_JOINED ")) {
            String[] parts = message.split("\\s+");
            listener.onSpectateJoined(parts[1], parts[2], parts[3]);
        }
    }

    // Swaps this client onto a brand new connection to a different Game Server Shard,
    // authenticating with a one-time resume token instead of username/password - that
    // shard already knows who this token belongs to (GameAllocator told it via Redis
    // pub/sub before the client was ever redirected here). Runs synchronously on the
    // reader thread (called from dispatch()), so by the time this returns, the next
    // readLoop() iteration is already reading from the new connection.
    private boolean reconnectTo(String host, int port, String token) {
        WebSocketConnection old = connection;
        try {
            Socket socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            WebSocketConnection redirected = WebSocketHandshake.clientHandshake(socket, host, port, "/");
            redirected.sendText("RESUME " + token);
            String reply = redirected.receiveText();
            if (reply == null || !reply.startsWith("RESUME_OK")) {
                log.log("Resume failed at " + host + ":" + port + ": " + reply);
                redirected.close();
                return false;
            }
            connection = redirected;
            old.close();
            log.log("Redirected to game host " + host + ":" + port);
            return true;
        } catch (IOException e) {
            log.log("Failed to connect to game host " + host + ":" + port + ": " + e.getMessage());
            return false;
        }
    }

    public void sendPlay() {
        send("PLAY");
    }

    public void sendClick(int row, int col) {
        send("CLICK " + row + " " + col);
    }

    public void sendJump(int row, int col) {
        send("JUMP " + row + " " + col);
    }

    public void sendDeselect() {
        send("DESELECT");
    }

    public void sendCreateRoom() {
        send("CREATE_ROOM");
    }

    public void sendJoinRoom(String roomId) {
        send("JOIN_ROOM " + roomId);
    }

    private void send(String message) {
        try {
            connection.sendText(message);
        } catch (IOException e) {
            log.log("Failed to send to server: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        running = false;
        connection.close();
    }
}
