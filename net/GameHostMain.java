// Headless entry point for a Game Server Shard (course diagram component #5): a
// separate process that only hosts matches it's told about via Redis pub/sub - it
// never accepts logins and never runs matchmaking (that's ServerMain/MatchmakingServer,
// the Gateway). See GameAllocator's remote mode and GameHostServer.
public class GameHostMain {
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
        System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
        System.setErr(new java.io.PrintStream(System.err, true, "UTF-8"));

        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5100;
        String dbLocation = args.length > 1 ? args[1] : "users.db";
        // What this instance tells the Gateway to hand clients - and what it subscribes
        // to on Redis pub/sub ("gamehost:<selfAddress>"). Must be reachable *from the
        // client*, not just from the Gateway - in Docker/K8s this needs to be the
        // instance's real externally-reachable address, not "localhost".
        String selfAddress = args.length > 2 ? args[2] : ("localhost:" + port);

        UserDatabase userDatabase = new UserDatabase(dbLocation);
        ActivityLog log = new ActivityLog("gamehost-" + port + ".log");

        String redisUrl = System.getenv("REDIS_URL");
        if (redisUrl == null || redisUrl.isBlank()) {
            throw new IllegalStateException(
                    "GameHostMain requires REDIS_URL - it only ever learns about matches via Redis pub/sub.");
        }
        String[] hostPort = redisUrl.split(":", 2);
        int redisPort = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 6379;
        // Two separate connections on purpose - see GameHostServer's field comment.
        RedisClient redis = new RedisClient(hostPort[0], redisPort);
        redis.ping();
        RedisClient subscribeConnection = new RedisClient(hostPort[0], redisPort);

        GameHostServer server = new GameHostServer(port, userDatabase, log, redis, subscribeConnection, selfAddress,
                () -> new GameSession(new BoardParser().parse(STARTING_POSITION)));
        server.start();

        log.log("GameHostServer is up on port " + port + " as \"" + selfAddress + "\", subscribed via Redis at " + redisUrl + ".");
        Thread.currentThread().join();
    }
}
