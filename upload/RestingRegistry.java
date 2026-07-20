import java.util.HashMap;
import java.util.Map;

// Tracks squares whose piece is on cooldown (after capturing, or after
// landing from a jump) - it stays on the board but cannot be
// selected/moved/jumped until the cooldown expires.
public class RestingRegistry {
    private static final class RestInfo {
        final long until;
        final long duration;
        final PieceVisualState state;

        RestInfo(long until, long duration, PieceVisualState state) {
            this.until = until;
            this.duration = duration;
            this.state = state;
        }
    }

    private final Map<Position, RestInfo> resting = new HashMap<>();

    public boolean isResting(Position pos, long currentTime) {
        RestInfo info = resting.get(pos);
        return info != null && currentTime < info.until;
    }

    public void rest(Position pos, long currentTime, long duration, PieceVisualState state) {
        resting.put(pos, new RestInfo(currentTime + duration, duration, state));
    }

    public PieceVisualState stateOf(Position pos) {
        RestInfo info = resting.get(pos);
        return info == null ? PieceVisualState.IDLE : info.state;
    }

    public long elapsedOf(Position pos, long currentTime) {
        RestInfo info = resting.get(pos);
        return info == null ? 0 : info.duration - (info.until - currentTime);
    }

    public long durationOf(Position pos) {
        RestInfo info = resting.get(pos);
        return info == null ? 0 : info.duration;
    }
}
