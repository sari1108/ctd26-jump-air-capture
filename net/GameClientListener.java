// Everything a GameClient can tell its window about, across the whole
// lobby -> matchmaking -> match lifecycle. Default no-op methods so callers
// only implement what they care about.
public interface GameClientListener {
    default void onSnapshot(GameSnapshot snapshot) {}
    default void onNames(String whiteName, String blackName) {}
    default void onStatus(String status) {}
    default void onSearching() {}
    default void onMatchFound(String color, String opponentUsername, int opponentElo) {}
    // Only fires under a real process-level split (see GameAllocator's remote mode):
    // the Gateway handed off to a Game Server Shard, but reconnecting to it failed.
    default void onRedirectFailed(String hostPort) {}
    default void onNoMatch() {}
    default void onOpponentDisconnected(int secondsRemaining) {}
    default void onMoveResolved(MoveEvent event) {}
    default void onGameOver(GameOverEvent event) {}
    default void onGameStarted(GameStartedEvent event) {}
    default void onRoomCreated(String roomId) {}
    default void onRoomNotFound(String roomId) {}
    default void onSpectateJoined(String roomId, String whiteUsername, String blackUsername) {}
}
