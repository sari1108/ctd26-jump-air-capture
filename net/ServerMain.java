// Headless entry point for the single-process server: accepts any number of
// WebSocket clients, logs each in against SQLite, and pairs them up by ELO
// into independent matches (see MatchmakingServer). No window, no Img - the
// server never draws anything, only the clients do.
public class ServerMain {
    private static final String STARTING_POSITION =
            "bR bN bB bQ bK bB bN bR\n" +
            "bP bP bP bP bP bP bP bP\n" +
            ".  .  .  .  .  .  .  .\n" +
            ".  .  .  .  .  .  .  .\n" +
            ".  .  .  .  .  .  .  .\n" +
            ".  .  .  .  .  .  .  .\n" +
            "wP wP wP wP wP wP wP wP\n" +
            "wR wN wB wQ wK wB wN wR\n";

    public static void main(String[] args) throws Exception {
        // Windows' console defaults to a legacy codepage, not UTF-8 - without this,
        // any non-ASCII username (e.g. Hebrew) a client sends prints here as mojibake.
        System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
        System.setErr(new java.io.PrintStream(System.err, true, "UTF-8"));

        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        String dbPath = args.length > 1 ? args[1] : "users.db";

        UserDatabase userDatabase = new UserDatabase(dbPath);
        ActivityLog log = new ActivityLog("server.log");

        // REDIS_URL ("host:port"), when set, moves the matchmaking queue and room
        // registry's shared state onto Redis instead of local JVM memory - see
        // Server_Design.md Q2. Unset (native/local runs), everything behaves exactly
        // as before: a plain in-memory queue/map, no Redis dependency at all.
        RedisClient redis = null;
        String redisUrl = System.getenv("REDIS_URL");
        if (redisUrl != null && !redisUrl.isBlank()) {
            String[] hostPort = redisUrl.split(":", 2);
            redis = new RedisClient(hostPort[0], hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 6379);
            redis.ping();
            log.log("Connected to Redis at " + redisUrl + " - matchmaking queue and room registry are Redis-backed.");
        }

        MatchmakingServer server = new MatchmakingServer(port, userDatabase, log,
                () -> new GameSession(new BoardParser().parse(STARTING_POSITION)), redis);
        server.start();

        HealthServer health = new HealthServer(8080, server);
        health.start();
        log.log("Health/metrics endpoint on port 8080 (/healthz, /metrics).");

        log.log("Server is up on port " + port + ". Waiting for players to log in and hit Play...");
        Thread.currentThread().join();
    }
}
