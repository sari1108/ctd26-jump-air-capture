import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Pure data snapshot of the current state for the View to render - positions
// are in the fixed logical grid (GameConfig.CELL_SIZE per cell), never real
// screen pixels; mapping that onto an actual window is the View's job alone.
public class SnapshotBuilder {
    private final Board board;
    private final Map<PieceType, MoveValidator> validators;
    private final PendingMoveQueue pendingMoves;
    private final AirborneRegistry airborneRegistry;
    private final RestingRegistry restingRegistry;
    private final PieceSelection selection;

    public SnapshotBuilder(Board board, Map<PieceType, MoveValidator> validators, PendingMoveQueue pendingMoves,
                            AirborneRegistry airborneRegistry, RestingRegistry restingRegistry, PieceSelection selection) {
        this.board = board;
        this.validators = validators;
        this.pendingMoves = pendingMoves;
        this.airborneRegistry = airborneRegistry;
        this.restingRegistry = restingRegistry;
        this.selection = selection;
    }

    public GameSnapshot build(long currentTime, boolean isGameOver, String winner, long gameOverAt,
                               Position rejectedPosition, long rejectedAt) {
        List<PieceSnapshot> pieces = new ArrayList<>();

        for (int r = 0; r < board.getRows(); r++) {
            for (int c = 0; c < board.getCols(); c++) {
                if (pendingMoves.hasMoveFrom(r, c)) continue;
                Piece p = board.getPiece(r, c);
                if (p == null || p.isEmpty()) continue;

                Position pos = new Position(r, c);
                boolean resting = restingRegistry.isResting(pos, currentTime);
                PieceVisualState state = resting ? restingRegistry.stateOf(pos) : PieceVisualState.IDLE;
                long elapsed = resting ? restingRegistry.elapsedOf(pos, currentTime) : 0;
                long duration = resting ? restingRegistry.durationOf(pos) : 0;

                pieces.add(new PieceSnapshot(r + "_" + c, p.getType(), p.getColor(),
                        c * GameConfig.CELL_SIZE, r * GameConfig.CELL_SIZE,
                        state, elapsed, duration));
            }
        }

        for (PendingMove move : pendingMoves.all()) {
            long total = move.getArrivalTime() - move.getStartTime();
            double t = total <= 0 ? 1.0 : Math.min(1.0, (double) (currentTime - move.getStartTime()) / total);
            double row = move.getFromRow() + (move.getToRow() - move.getFromRow()) * t;
            double col = move.getFromCol() + (move.getToCol() - move.getFromCol()) * t;
            pieces.add(new PieceSnapshot(move.getFromRow() + "_" + move.getFromCol(),
                    move.getPiece().getType(), move.getPiece().getColor(),
                    col * GameConfig.CELL_SIZE, row * GameConfig.CELL_SIZE,
                    PieceVisualState.MOVE, currentTime - move.getStartTime(), total));
        }

        for (Map.Entry<Position, AirbornePiece> entry : airborneRegistry.all().entrySet()) {
            Position pos = entry.getKey();
            AirbornePiece ap = entry.getValue();
            long elapsed = GameConfig.JUMP_DURATION_MS - (ap.getExpiryTime() - currentTime);
            pieces.add(new PieceSnapshot(pos.getRow() + "_" + pos.getCol(),
                    ap.getPiece().getType(), ap.getPiece().getColor(),
                    pos.getCol() * GameConfig.CELL_SIZE, pos.getRow() * GameConfig.CELL_SIZE,
                    PieceVisualState.JUMP, elapsed, GameConfig.JUMP_DURATION_MS));
        }

        Position selected = selection.isActive() ? selection.getPosition() : null;
        Position rejected = (rejectedPosition != null && currentTime - rejectedAt <= GameConfig.REJECTED_MOVE_FLASH_MS)
                ? rejectedPosition : null;
        List<Position> legalMoves = legalDestinationsFromSelection();
        long msSinceGameOver = (isGameOver && gameOverAt >= 0) ? currentTime - gameOverAt : 0;
        return new GameSnapshot(board.getRows(), board.getCols(), pieces, selected, rejected, legalMoves,
                isGameOver, winner, currentTime, msSinceGameOver);
    }

    // Every square the currently-selected piece could legally move to right now -
    // purely informational for the View (e.g. to highlight them); doesn't affect play.
    private List<Position> legalDestinationsFromSelection() {
        List<Position> result = new ArrayList<>();
        if (!selection.isActive()) return result;

        Position from = selection.getPosition();
        Piece piece = selection.getPiece();
        MoveValidator v = validators.get(piece.getType());
        if (v == null) return result;

        for (int r = 0; r < board.getRows(); r++) {
            for (int c = 0; c < board.getCols(); c++) {
                if (r == from.getRow() && c == from.getCol()) continue;
                if (!v.isValid(from.getRow(), from.getCol(), r, c, board)) continue;

                Position dest = new Position(r, c);
                if (airborneRegistry.isAirborne(dest)
                        && airborneRegistry.get(dest).getPiece().getColor() == piece.getColor()) {
                    continue;
                }
                result.add(dest);
            }
        }
        return result;
    }
}
