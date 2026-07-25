// Bus subscriber: the start/end fade animations (BoardRenderer.drawStartFade /
// drawGameOver) are timed from here, not from the engine's raw internal clock -
// "how long since TOPIC_GAME_STARTED/TOPIC_GAME_OVER actually fired" is exactly
// what those animations are supposed to be counting from.
public class AnimationClock {
    private long startedAt = -1;
    private long gameOverAt = -1;

    public void subscribe(Bus bus) {
        bus.subscribe(GameSession.TOPIC_GAME_STARTED, payload -> onGameStarted((GameStartedEvent) payload));
        bus.subscribe(GameSession.TOPIC_GAME_OVER, payload -> onGameOver((GameOverEvent) payload));
    }

    private void onGameStarted(GameStartedEvent e) {
        startedAt = e.time;
    }

    private void onGameOver(GameOverEvent e) {
        if (gameOverAt < 0) gameOverAt = e.time;
    }

    // Before the start event has fired, this reads 0 - the fade correctly shows
    // full black rather than assuming a start that hasn't happened yet.
    public long msSinceStart(long currentTime) {
        return startedAt < 0 ? 0 : Math.max(0, currentTime - startedAt);
    }

    public long msSinceGameOver(long currentTime) {
        return gameOverAt < 0 ? 0 : Math.max(0, currentTime - gameOverAt);
    }
}
