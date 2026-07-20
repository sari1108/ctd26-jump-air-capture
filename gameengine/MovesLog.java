import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Bus subscriber: keeps an ordered history of resolved moves per color, with a
// simplified algebraic-style notation (no disambiguation, check/mate marks,
// or castling - just piece letter + optional "x" + destination square).
public class MovesLog {
    public static final class Entry {
        public final PieceColor color;
        public final String notation;
        public final long time;

        Entry(PieceColor color, String notation, long time) {
            this.color = color;
            this.notation = notation;
            this.time = time;
        }
    }

    private final int boardRows;
    private final List<Entry> entries = new ArrayList<>();

    public MovesLog(int boardRows) {
        this.boardRows = boardRows;
    }

    public void subscribe(Bus bus) {
        bus.subscribe(GameSession.TOPIC_MOVE_RESOLVED, payload -> onMoveResolved((MoveEvent) payload));
    }

    private void onMoveResolved(MoveEvent e) {
        entries.add(new Entry(e.piece.getColor(), notation(e.piece, e.from, e.to, e.wasCapture), e.time));
    }

    public List<Entry> entriesFor(PieceColor color) {
        List<Entry> result = new ArrayList<>();
        for (Entry e : entries) {
            if (e.color == color) result.add(e);
        }
        return Collections.unmodifiableList(result);
    }

    private String notation(Piece piece, Position from, Position to, boolean wasCapture) {
        String dest = square(to);
        if (piece.getType() == PieceType.PAWN) {
            return wasCapture ? (square(from).charAt(0) + "x" + dest) : dest;
        }
        return "" + piece.getType().getCode() + (wasCapture ? "x" : "") + dest;
    }

    private String square(Position pos) {
        char file = (char) ('a' + pos.getCol());
        int rank = boardRows - pos.getRow();
        return "" + file + rank;
    }
}
