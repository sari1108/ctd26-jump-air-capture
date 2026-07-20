import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class PendingMoveQueue {
    private final List<PendingMove> moves = new ArrayList<>();

    public boolean isEmpty() { return moves.isEmpty(); }

    public void add(PendingMove move) { moves.add(move); }

    public List<PendingMove> all() { return Collections.unmodifiableList(moves); }

    public boolean hasMoveFrom(int row, int col) {
        for (PendingMove m : moves) {
            if (m.getFromRow() == row && m.getFromCol() == col) return true;
        }
        return false;
    }

    public List<PendingMove> extractArrived(long currentTime) {
        List<PendingMove> arrived = new ArrayList<>();
        Iterator<PendingMove> it = moves.iterator();
        while (it.hasNext()) {
            PendingMove m = it.next();
            if (m.hasArrivedBy(currentTime)) {
                arrived.add(m);
                it.remove();
            }
        }
        arrived.sort(Comparator.comparingLong(PendingMove::getArrivalTime));
        return arrived;
    }
}
