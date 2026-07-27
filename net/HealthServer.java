import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;

// Observability (course slide component #6: logs, metrics, health checks, load tests) and
// the diagram's "REST/HTTP is used for ... history" - both answered with the JDK's own
// built-in HttpServer, no new dependency, on a port separate from the WebSocket game
// traffic:
//   GET /healthz          -> 200 "ok" once the WebSocket accept loop is actually
//                            listening. What a load balancer or Kubernetes
//                            readinessProbe/livenessProbe (Server_Design.md Q4) polls.
//   GET /metrics           -> plain-text key/value pairs (Prometheus exposition format).
//   GET /history?user=X   -> JSON array of that user's past games (from UserDatabase's
//                            "games" table) - real REST/HTTP for a non-realtime request,
//                            exactly the diagram's example, not another WebSocket message.
final class HealthServer {
    private final int port;
    private final MatchmakingServer server;
    private final UserDatabase userDatabase;
    private final long startedAt = System.currentTimeMillis();

    HealthServer(int port, MatchmakingServer server, UserDatabase userDatabase) {
        this.port = port;
        this.server = server;
        this.userDatabase = userDatabase;
    }

    void start() throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);

        http.createContext("/healthz", exchange -> {
            respond(exchange, 200, "text/plain; charset=utf-8", "ok");
        });

        http.createContext("/metrics", exchange -> {
            String text = "uptime_seconds " + (System.currentTimeMillis() - startedAt) / 1000 + "\n"
                    + "matchmaking_queue_depth " + server.queueDepth() + "\n"
                    + "matches_started_total " + server.matchesStartedCount() + "\n"
                    + "active_rooms " + server.activeRoomCount() + "\n";
            respond(exchange, 200, "text/plain; charset=utf-8", text);
        });

        http.createContext("/history", exchange -> {
            String user = queryParam(exchange.getRequestURI().getRawQuery(), "user");
            if (user == null || user.isBlank()) {
                respond(exchange, 400, "application/json; charset=utf-8", "{\"error\":\"missing 'user' query parameter\"}");
                return;
            }
            try {
                List<UserDatabase.GameRecord> games = userDatabase.listGames(user, 20);
                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < games.size(); i++) {
                    if (i > 0) json.append(',');
                    UserDatabase.GameRecord g = games.get(i);
                    json.append("{\"white\":\"").append(jsonEscape(g.whiteUsername)).append("\",")
                            .append("\"black\":\"").append(jsonEscape(g.blackUsername)).append("\",")
                            .append("\"winner\":\"").append(jsonEscape(g.winnerColor)).append("\",")
                            .append("\"endedAt\":").append(g.endedAt).append('}');
                }
                json.append(']');
                respond(exchange, 200, "application/json; charset=utf-8", json.toString());
            } catch (SQLException e) {
                respond(exchange, 500, "application/json; charset=utf-8",
                        "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
            }
        });

        http.setExecutor(null);
        http.start();
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = pair.substring(0, eq);
            if (k.equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // UTF-8 explicitly, not the platform default - this codebase has a real prior
    // mojibake-bug history with Hebrew text over Windows-default encodings.
    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
