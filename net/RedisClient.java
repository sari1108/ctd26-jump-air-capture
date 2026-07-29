import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// A minimal Redis client, hand-rolled the same way this project hand-rolls its own
// WebSocket layer instead of pulling in a framework: one TCP connection, the RESP
// wire protocol (see https://redis.io/docs/reference/protocol-spec/), just the handful
// of commands MatchmakingServer/RoomRegistry need to mirror their shared state
// (queue entries, room records) into a real external store instead of local JVM heap.
final class RedisClient implements AutoCloseable {
    private final Socket socket;
    private final BufferedInputStream in;
    private final OutputStream out;

    RedisClient(String host, int port) throws IOException {
        socket = new Socket(host, port);
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    synchronized void hset(String key, String field, String value) throws IOException {
        command("HSET", key, field, value);
    }

    synchronized Map<String, String> hgetall(String key) throws IOException {
        Object reply = command("HGETALL", key);
        Map<String, String> map = new LinkedHashMap<>();
        if (reply instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) reply;
            for (int i = 0; i + 1 < items.size(); i += 2) {
                map.put(String.valueOf(items.get(i)), String.valueOf(items.get(i + 1)));
            }
        }
        return map;
    }

    synchronized void del(String key) throws IOException {
        command("DEL", key);
    }

    synchronized void sadd(String key, String member) throws IOException {
        command("SADD", key, member);
    }

    synchronized void srem(String key, String member) throws IOException {
        command("SREM", key, member);
    }

    synchronized Set<String> smembers(String key) throws IOException {
        Object reply = command("SMEMBERS", key);
        Set<String> set = new LinkedHashSet<>();
        if (reply instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) reply;
            for (Object item : items) set.add(String.valueOf(item));
        }
        return set;
    }

    // Cheap liveness check used at startup - fail fast with a clear error rather than
    // discovering a bad REDIS_URL only once the first real matchmaking request breaks.
    synchronized void ping() throws IOException {
        command("PING");
    }

    // Pub/sub: the inter-service messaging layer the course diagram calls for (NATS or
    // Redis Pub/Sub) - a Gateway process telling a specific Game-hosting instance "you
    // now own this match" (see GameAllocator's remote mode / GameHostServer). Real,
    // separate from the hset/sadd data-mirroring commands above (those are a shared
    // *store*; this is a message *bus* - Server_Design.md draws the same distinction).
    synchronized void publish(String channel, String message) throws IOException {
        command("PUBLISH", channel, message);
    }

    // Blocks forever, delivering each message published to `channel` to onMessage - call
    // this from its own dedicated thread on its own dedicated RedisClient instance (once
    // subscribed, a connection stops accepting ordinary commands, so it can't be shared
    // with the hset/sadd calls above).
    void subscribeLoop(String channel, java.util.function.Consumer<String> onMessage) throws IOException {
        synchronized (this) {
            command("SUBSCRIBE", channel); // returns the ["subscribe", channel, count] ack
        }
        while (true) {
            Object reply = readReply();
            if (reply instanceof List) {
                List<?> arr = (List<?>) reply;
                if (arr.size() >= 3 && "message".equals(arr.get(0))) {
                    onMessage.accept(String.valueOf(arr.get(2)));
                }
            }
        }
    }

    private Object command(String... args) throws IOException {
        StringBuilder req = new StringBuilder();
        req.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            req.append('$').append(bytes.length).append("\r\n").append(arg).append("\r\n");
        }
        out.write(req.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        return readReply();
    }

    private Object readReply() throws IOException {
        int type = in.read();
        if (type == -1) throw new IOException("Redis connection closed");
        String line = readLine();
        switch (type) {
            case '+': // simple string
                return line;
            case '-': // error
                throw new IOException("Redis error: " + line);
            case ':': // integer
                return Long.parseLong(line);
            case '$': { // bulk string
                int len = Integer.parseInt(line);
                if (len == -1) return null;
                byte[] data = new byte[len];
                int read = 0;
                while (read < len) {
                    int n = in.read(data, read, len - read);
                    if (n == -1) throw new IOException("Redis connection closed mid-reply");
                    read += n;
                }
                in.read(); // \r
                in.read(); // \n
                return new String(data, StandardCharsets.UTF_8);
            }
            case '*': { // array
                int count = Integer.parseInt(line);
                if (count == -1) return null;
                List<Object> items = new ArrayList<>(count);
                for (int i = 0; i < count; i++) items.add(readReply());
                return items;
            }
            default:
                throw new IOException("Unexpected Redis reply type: " + (char) type);
        }
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\r') sb.append((char) c);
        in.read(); // consume \n
        return sb.toString();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
