// Bus subscriber: tallies the "cost" (standard piece values) of everything each
// color has captured. Doesn't touch the board - purely derived from bus events.
public class ScoreTracker {
    private int whiteScore = 0;
    private int blackScore = 0;

    public void subscribe(Bus bus) {
        bus.subscribe(GameSession.TOPIC_MOVE_RESOLVED, payload -> onMoveResolved((MoveEvent) payload));
    }

    private void onMoveResolved(MoveEvent e) {
        if (!e.wasCapture || e.capturedPiece == null) return;
        int value = valueOf(e.capturedPiece.getType());
        if (e.piece.getColor() == PieceColor.WHITE) {
            whiteScore += value;
        } else {
            blackScore += value;
        }
    }

    public int getScore(PieceColor color) {
        return color == PieceColor.WHITE ? whiteScore : blackScore;
    }

    private static int valueOf(PieceType type) {
        switch (type) {
            case PAWN: return 1;
            case KNIGHT: return 3;
            case BISHOP: return 3;
            case ROOK: return 5;
            case QUEEN: return 9;
            default: return 0;
        }
    }
}
