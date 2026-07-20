// Payload published on GameSession.TOPIC_GAME_STARTED.
public final class GameStartedEvent {
    public final long time;

    public GameStartedEvent(long time) {
        this.time = time;
    }
}
