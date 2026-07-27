import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

// Observability (course slide component #6: logs, metrics, health checks, load tests).
// ActivityLog already covers logs; this covers the other two with the smallest thing
// that could work - the JDK's own built-in HttpServer, no new dependency, on a port
// separate from the WebSocket game traffic:
//   GET /healthz  -> 200 "ok" once the WebSocket accept loop is actually listening.
//                    This is exactly what a load balancer or a Kubernetes
//                    readinessProbe/livenessProbe (see Server_Design.md Q4) polls to
//                    decide whether to route new players to this instance.
//   GET /metrics  -> plain-text key/value pairs (Prometheus exposition format), enough
//                    to see queue depth and match throughput without attaching a debugger.
final class HealthServer {
    private final int port;
    private final MatchmakingServer server;
    private final long startedAt = System.currentTimeMillis();

    HealthServer(int port, MatchmakingServer server) {
        this.port = port;
        this.server = server;
    }

    void start() throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);

        http.createContext("/healthz", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        http.createContext("/metrics", exchange -> {
            String text = "uptime_seconds " + (System.currentTimeMillis() - startedAt) / 1000 + "\n"
                    + "matchmaking_queue_depth " + server.queueDepth() + "\n"
                    + "matches_started_total " + server.matchesStartedCount() + "\n"
                    + "active_rooms " + server.activeRoomCount() + "\n";
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        http.setExecutor(null);
        http.start();
    }
}
