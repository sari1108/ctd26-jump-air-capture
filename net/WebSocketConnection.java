import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

// One open WebSocket connection, after the HTTP upgrade handshake is done.
// Client-side frames must be masked, server-side frames must not (RFC 6455) -
// isClientSide picks which of those this end of the connection behaves as.
class WebSocketConnection implements AutoCloseable {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final boolean isClientSide;
    private volatile boolean closed = false;

    WebSocketConnection(Socket socket, InputStream in, OutputStream out, boolean isClientSide) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.isClientSide = isClientSide;
    }

    synchronized void sendText(String text) throws IOException {
        if (closed) throw new IOException("connection closed");
        WebSocketFrame.writeText(out, text, isClientSide);
    }

    // Blocks for the next text message. Returns null once the peer closes
    // the connection (or a close frame is received). Transparently answers
    // pings and skips them.
    String receiveText() throws IOException {
        while (true) {
            WebSocketFrame frame = WebSocketFrame.read(in);
            if (frame == null) return null;
            switch (frame.opcode) {
                case WebSocketFrame.OPCODE_TEXT:
                    return frame.textPayload();
                case WebSocketFrame.OPCODE_CLOSE:
                    close();
                    return null;
                case WebSocketFrame.OPCODE_PING:
                    synchronized (this) {
                        WebSocketFrame.writePong(out, frame.payload, isClientSide);
                    }
                    break;
                default:
                    break; // ignore pong / unsupported opcodes
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            WebSocketFrame.writeClose(out, isClientSide);
        } catch (IOException ignored) {
            // best-effort close frame
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
