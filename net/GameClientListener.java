// Everything a GameClient can tell its window about, across the whole
// lobby -> matchmaking -> match lifecycle. Default no-op methods so callers
// only implement what they care about.
public interface GameClientListener {
    default void onSnapshot(GameSnapshot snapshot) {}
    default void onNames(String whiteName, String blackName) {}
    default void onStatus(String status) {}
    default void onSearching() {}
    default void onMatchFound(String color, String opponentUsername, int opponentElo) {}
    default void onNoMatch() {}
    default void onOpponentDisconnected(int secondsRemaining) {}
}
