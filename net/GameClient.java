import java.io.IOException;
import java.net.Socket;

// Client side of the connection to MatchmakingServer. Logs in (username+
// password) as a blocking exchange right after the WebSocket handshake -
// only once that succeeds does the async reader thread start, dispatching
// every incoming message to the listener and forwarding PLAY/CLICK/JUMP/
// DESELECT commands the other way.
public class GameClient implements AutoCloseable {
    private final WebSocketConnection connection;
    private final int elo;
    private volatile boolean running = true;

    public GameClient(String host, int port, String username, String password,
                       GameClientListener listener) throws IOException {
        Socket socket = new Socket(host, port);
        connection = WebSocketHandshake.clientHandshake(socket, host, port, "/");

        connection.sendText("LOGIN " + username + " " + password);
        String reply = connection.receiveText();
        if (reply == null || !reply.startsWith("LOGIN_OK")) {
            String reason = reply == null ? "no_response" : reply;
            connection.close();
            throw new IOException("Login failed: " + reason);
        }
        this.elo = Integer.parseInt(reply.split("\\s+")[1]);

        Thread reader = new Thread(() -> readLoop(listener), "game-client-read");
        reader.setDaemon(true);
        reader.start();
    }

    public int getElo() {
        return elo;
    }

    private void readLoop(GameClientListener listener) {
        try {
            String message;
            while (running && (message = connection.receiveText()) != null) {
                dispatch(message, listener);
            }
        } catch (IOException e) {
            if (running) System.out.println("Disconnected from server: " + e.getMessage());
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
            listener.onMatchFound(parts[1], parts[2], Integer.parseInt(parts[3]));
        } else if (message.equals("NO_MATCH")) {
            listener.onNoMatch();
        } else if (message.startsWith("OPPONENT_DISCONNECTED")) {
            listener.onOpponentDisconnected(Integer.parseInt(message.split("\\s+")[1]));
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

    private void send(String message) {
        try {
            connection.sendText(message);
        } catch (IOException e) {
            System.out.println("Failed to send to server: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        running = false;
        connection.close();
    }
}
