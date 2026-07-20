// Payload published on GameSession.TOPIC_GAME_OVER.
public final class GameOverEvent {
    public final String winnerColor;
    public final long time;

    public GameOverEvent(String winnerColor, long time) {
        this.winnerColor = winnerColor;
        this.time = time;
    }
}
