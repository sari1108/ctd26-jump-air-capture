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
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        String dbPath = args.length > 1 ? args[1] : "users.db";

        UserDatabase userDatabase = new UserDatabase(dbPath);
        ActivityLog log = new ActivityLog("server.log");

        MatchmakingServer server = new MatchmakingServer(port, userDatabase, log,
                () -> new GameSession(new BoardParser().parse(STARTING_POSITION)));
        server.start();

        log.log("Server is up on port " + port + ". Waiting for players to log in and hit Play...");
        Thread.currentThread().join();
    }
}
