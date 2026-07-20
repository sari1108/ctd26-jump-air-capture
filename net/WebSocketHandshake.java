import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

// The RFC 6455 opening handshake: an HTTP GET with an Upgrade header on the
// client side, answered with "101 Switching Protocols" on the server side.
// Implemented from plain sockets/streams - no WebSocket library available,
// only the standard JDK's SHA-1 and Base64.
final class WebSocketHandshake {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketHandshake() {
    }

    static String acceptFor(String clientKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((clientKey + GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    // Server side: read the HTTP upgrade request off the socket and answer it.
    static WebSocketConnection serverHandshake(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        Map<String, String> headers = new HashMap<>();
        String line = reader.readLine();
        if (line == null || !line.startsWith("GET")) {
            throw new IOException("Not a WebSocket upgrade request: " + line);
        }
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
        }

        String key = headers.get("sec-websocket-key");
        if (key == null) {
            throw new IOException("Missing Sec-WebSocket-Key header");
        }

        OutputStream out = socket.getOutputStream();
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptFor(key) + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        return new WebSocketConnection(socket, socket.getInputStream(), out, false);
    }

    // Client side: send the HTTP upgrade request and verify the server's reply.
    static WebSocketConnection clientHandshake(Socket socket, String host, int port, String path) throws IOException {
        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);

        OutputStream out = socket.getOutputStream();
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String statusLine = reader.readLine();
        if (statusLine == null || !statusLine.contains("101")) {
            throw new IOException("WebSocket handshake failed: " + statusLine);
        }
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
        }
        String expectedAccept = acceptFor(key);
        if (!expectedAccept.equals(headers.get("sec-websocket-accept"))) {
            throw new IOException("WebSocket handshake accept-key mismatch");
        }

        return new WebSocketConnection(socket, socket.getInputStream(), out, true);
    }
}
