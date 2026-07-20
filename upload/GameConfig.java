public final class GameConfig {
    private GameConfig() {}

    public static final int CELL_SIZE = 100;
    public static final long MOVE_DURATION_MS = 1000; // per cell of travel distance
    public static final long JUMP_DURATION_MS = 1000;
    public static final long LONG_REST_DURATION_MS = 1000; // cooldown after capturing, before the piece can act again
    public static final long SHORT_REST_DURATION_MS = 500; // cooldown after landing from a jump
    public static final long REJECTED_MOVE_FLASH_MS = 400; // how long an illegal-move attempt flashes on screen
}
