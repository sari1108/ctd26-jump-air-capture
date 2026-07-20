import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

// Minimal RFC 6455 text-frame codec: only what a local two-player game needs
// (single, unfragmented text frames), built on plain streams so no
// WebSocket library is required beyond the standard JDK.
final class WebSocketFrame {
    static final int OPCODE_TEXT = 0x1;
    static final int OPCODE_CLOSE = 0x8;
    static final int OPCODE_PING = 0x9;
    static final int OPCODE_PONG = 0xA;

    final int opcode;
    final byte[] payload;

    private WebSocketFrame(int opcode, byte[] payload) {
        this.opcode = opcode;
        this.payload = payload;
    }

    static void writeText(OutputStream out, String text, boolean maskAsClient) throws IOException {
        write(out, OPCODE_TEXT, text.getBytes(StandardCharsets.UTF_8), maskAsClient);
    }

    static void writeClose(OutputStream out, boolean maskAsClient) throws IOException {
        write(out, OPCODE_CLOSE, new byte[0], maskAsClient);
    }

    static void writePong(OutputStream out, byte[] payload, boolean maskAsClient) throws IOException {
        write(out, OPCODE_PONG, payload, maskAsClient);
    }

    private static void write(OutputStream out, int opcode, byte[] payload, boolean maskAsClient) throws IOException {
        int len = payload.length;
        out.write(0x80 | opcode); // FIN=1, RSV=0, opcode

        int maskBit = maskAsClient ? 0x80 : 0x00;
        if (len <= 125) {
            out.write(maskBit | len);
        } else if (len <= 65535) {
            out.write(maskBit | 126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(maskBit | 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((len >>> shift) & 0xFF));
            }
        }

        if (maskAsClient) {
            byte[] maskKey = new byte[4];
            new java.security.SecureRandom().nextBytes(maskKey);
            out.write(maskKey);
            byte[] masked = new byte[len];
            for (int i = 0; i < len; i++) {
                masked[i] = (byte) (payload[i] ^ maskKey[i % 4]);
            }
            out.write(masked);
        } else {
            out.write(payload);
        }
        out.flush();
    }

    // Reads exactly one frame. Returns null if the peer closed the connection.
    static WebSocketFrame read(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 == -1) return null;
        int opcode = b0 & 0x0F;

        int b1 = readByte(in);
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = (readByte(in) << 8) | readByte(in);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | readByte(in);
            }
        }

        byte[] maskKey = null;
        if (masked) {
            maskKey = new byte[4];
            readFully(in, maskKey);
        }

        byte[] payload = new byte[(int) len];
        readFully(in, payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ maskKey[i % 4]);
            }
        }

        return new WebSocketFrame(opcode, payload);
    }

    String textPayload() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) throw new EOFException("WebSocket stream closed mid-frame");
        return b;
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int n = in.read(buffer, offset, buffer.length - offset);
            if (n == -1) throw new EOFException("WebSocket stream closed mid-frame");
            offset += n;
        }
    }
}
