import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class AirborneRegistry {
    private final Map<Position, AirbornePiece> airborne = new HashMap<>();

    public boolean isAirborne(Position pos) { return airborne.containsKey(pos); }

    public Map<Position, AirbornePiece> all() { return Collections.unmodifiableMap(airborne); }

    public AirbornePiece get(Position pos) { return airborne.get(pos); }

    public void activate(Position pos, Piece piece, long expiryTime) {
        airborne.put(pos, new AirbornePiece(piece, expiryTime));
    }

    public AirbornePiece resolveCapture(Position pos) {
        return airborne.remove(pos);
    }

    // Returns the positions that landed this tick, so the caller can react (e.g. start a rest cooldown there).
    public List<Position> landExpired(long currentTime, Board board) {
        List<Position> landed = new ArrayList<>();
        Iterator<Map.Entry<Position, AirbornePiece>> it = airborne.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Position, AirbornePiece> entry = it.next();
            if (entry.getValue().hasExpiredBy(currentTime)) {
                board.setPiece(entry.getKey().getRow(), entry.getKey().getCol(), entry.getValue().getPiece());
                landed.add(entry.getKey());
                it.remove();
            }
        }
        return landed;
    }
}
