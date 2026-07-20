import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GameSession {
    public static final String TOPIC_MOVE_RESOLVED = "move.resolved";
    public static final String TOPIC_GAME_OVER = "game.over";
    public static final String TOPIC_GAME_STARTED = "game.started";

    private final Board board;
    private final Map<PieceType, MoveValidator> validators;
    private final PromotionRule promotionRule;
    private final WinConditionRule winConditionRule;
    private final Bus bus = new Bus();

    private final PendingMoveQueue pendingMoves = new PendingMoveQueue();
    private final AirborneRegistry airborneRegistry = new AirborneRegistry();
    private final RestingRegistry restingRegistry = new RestingRegistry();

    private final PieceSelection selection = new PieceSelection();
    private long currentTime = 0;
    private boolean isGameOver = false;
    private String winner = null;
    private long gameOverAt = -1;
    private Position rejectedPosition = null;
    private long rejectedAt = -1;

    public GameSession(Board board) {
        this(board, ValidatorRegistry.standard(), new StandardPromotionRule(), new KingCaptureWinCondition());
    }

    // Extension point: plug in different piece rules (movement, promotion, win condition)
    // without touching the engine below - this is how a custom rule set gets swapped in.
    public GameSession(Board board, Map<PieceType, MoveValidator> validators,
                        PromotionRule promotionRule, WinConditionRule winConditionRule) {
        this.board = board;
        this.validators = validators;
        this.promotionRule = promotionRule;
        this.winConditionRule = winConditionRule;
    }

    public void click(int x, int y) {
        if (isGameOver) return;
        int r = y / GameConfig.CELL_SIZE;
        int c = x / GameConfig.CELL_SIZE;
        if (!board.isWithinBounds(r, c)) return;

        Piece clickedPiece = board.getPiece(r, c);
        if (!selection.isActive()) {
            trySelect(clickedPiece, r, c);
            return;
        }

        Position selectedPosition = selection.getPosition();
        Piece selectedPiece = selection.getPiece();
        MoveValidator v = validators.get(selectedPiece.getType());
        if (v != null && v.isValid(selectedPosition.getRow(), selectedPosition.getCol(), r, c, board)) {
            Position destPos = new Position(r, c);
            if (airborneRegistry.isAirborne(destPos)) {
                Piece airbornePiece = airborneRegistry.get(destPos).getPiece();
                if (selectedPiece.getColor() == airbornePiece.getColor()) {
                    flagRejected(destPos);
                    selection.clear();
                    return;
                }
            }

            int distance = Math.max(Math.abs(r - selectedPosition.getRow()), Math.abs(c - selectedPosition.getCol()));
            long startTime = currentTime;
            PendingMove move = new PendingMove(selectedPiece, selectedPosition.getRow(), selectedPosition.getCol(),
                    r, c, startTime, startTime + distance * GameConfig.MOVE_DURATION_MS);
            PendingMove resolved = resolveCollisions(move);
            if (resolved != null) {
                pendingMoves.add(resolved);
            }
            selection.clear();
        } else {
            Position beforeSelection = selectedPosition;
            trySelect(clickedPiece, r, c);
            Position afterSelection = selection.isActive() ? selection.getPosition() : null;
            if (afterSelection == null || afterSelection.equals(beforeSelection)) {
                flagRejected(new Position(r, c));
            }
        }
    }

    private void flagRejected(Position pos) {
        rejectedPosition = pos;
        rejectedAt = currentTime;
    }

    public void waitMs(long ms) {
        currentTime += ms;

        for (PendingMove move : pendingMoves.extractArrived(currentTime)) {
            resolveArrival(move);
        }

        for (Position landed : airborneRegistry.landExpired(currentTime, board)) {
            restingRegistry.rest(landed, currentTime, GameConfig.SHORT_REST_DURATION_MS, PieceVisualState.SHORT_REST);
        }
    }

    public void activateJump(int r, int c) {
        if (isGameOver) return;
        if (pendingMoves.hasMoveFrom(r, c)) return;

        Position pos = new Position(r, c);
        if (airborneRegistry.isAirborne(pos)) return;
        if (restingRegistry.isResting(pos, currentTime)) return;

        Piece jumpingPiece = board.getPiece(r, c);
        if (jumpingPiece != null && !jumpingPiece.isEmpty()) {
            board.setPiece(r, c, ChessPiece.empty());
            airborneRegistry.activate(pos, jumpingPiece, currentTime + GameConfig.JUMP_DURATION_MS);
        }
    }

    public Board getBoard() { return board; }

    public Position getSelectedPosition() { return selection.isActive() ? selection.getPosition() : null; }

    public void deselect() { selection.clear(); }

    // Everything that wants to react to this session (score, moves log, sound,
    // animations, ...) subscribes to this bus - the engine never knows who's listening.
    public Bus getBus() { return bus; }

    // Announces the game has begun. Call once, after wiring up bus subscribers.
    public void start() {
        bus.publish(TOPIC_GAME_STARTED, new GameStartedEvent(currentTime));
    }

    // Ends the game for a reason outside the board itself (e.g. a networked
    // opponent disconnecting and not returning in time) - same TOPIC_GAME_OVER
    // event and snapshot fields a king-capture win uses, so every existing
    // listener (score, sound, the game-over overlay, ELO) reacts the same way.
    public void forceGameOver(String winnerColor) {
        if (isGameOver) return;
        isGameOver = true;
        winner = winnerColor;
        gameOverAt = currentTime;
        bus.publish(TOPIC_GAME_OVER, new GameOverEvent(winner, currentTime));
    }

    private void trySelect(Piece clickedPiece, int r, int c) {
        Position pos = new Position(r, c);
        if (clickedPiece != null && !clickedPiece.isEmpty() && !pendingMoves.hasMoveFrom(r, c)
                && !restingRegistry.isResting(pos, currentTime)) {
            selection.set(clickedPiece, pos);
        }
    }

    // A move already in flight always has right of way over one just being queued.
    // Walking the new move's path cell by cell, the first pending move (of either
    // color) that also touches a cell decides the outcome: a same-color piece blocks
    // it (it stops one cell short), a different-color piece eats it outright (the new
    // move never happens at all - the piece stays put and gets captured when the
    // piece already in flight arrives).
    private PendingMove resolveCollisions(PendingMove incoming) {
        List<Position> path = MovePath.cellsOf(incoming);
        PieceColor color = incoming.getPiece().getColor();

        for (int i = 0; i < path.size(); i++) {
            Position cell = path.get(i);
            boolean blockedBySameColor = false;
            for (PendingMove existing : pendingMoves.all()) {
                if (!MovePath.cellsOf(existing).contains(cell)) continue;
                if (existing.getPiece().getColor() != color) return null;
                blockedBySameColor = true;
            }
            if (blockedBySameColor) {
                if (i == 0) {
                    return incoming.truncateTo(incoming.getFromRow(), incoming.getFromCol(), incoming.getStartTime());
                }
                Position stopAt = path.get(i - 1);
                long arrival = incoming.getStartTime() + (long) i * GameConfig.MOVE_DURATION_MS;
                return incoming.truncateTo(stopAt.getRow(), stopAt.getCol(), arrival);
            }
        }
        return incoming;
    }

    private void resolveArrival(PendingMove move) {
        Position targetPos = move.toPosition();
        board.setPiece(move.getFromRow(), move.getFromCol(), ChessPiece.empty());

        if (airborneRegistry.isAirborne(targetPos)) {
            resolveAirborneCapture(move, targetPos);
        } else {
            resolveNormalArrival(move);
        }
    }

    // The piece already airborne has right of way over whatever tries to land on its
    // square while it's still up: it survives and re-occupies the square, and the
    // arriving piece is the one that gets destroyed (it never lands at all).
    private void resolveAirborneCapture(PendingMove move, Position targetPos) {
        AirbornePiece airborneInfo = airborneRegistry.get(targetPos);
        Piece airbornePiece = airborneInfo.getPiece();
        Piece arrivingPiece = move.getPiece();
        if (arrivingPiece.getColor() != airbornePiece.getColor()) {
            airborneRegistry.resolveCapture(targetPos);
            if (winConditionRule.isGameOver(arrivingPiece)) {
                isGameOver = true;
                winner = airbornePiece.getColor().name();
                gameOverAt = currentTime;
            }
            board.setPiece(targetPos.getRow(), targetPos.getCol(), airbornePiece);
            restingRegistry.rest(targetPos, currentTime, GameConfig.LONG_REST_DURATION_MS, PieceVisualState.LONG_REST);

            bus.publish(TOPIC_MOVE_RESOLVED, new MoveEvent(airbornePiece, targetPos, targetPos, true, arrivingPiece, currentTime));
            if (isGameOver) bus.publish(TOPIC_GAME_OVER, new GameOverEvent(winner, currentTime));
        }
    }

    private void resolveNormalArrival(PendingMove move) {
        Piece target = board.getPiece(move.getToRow(), move.getToCol());
        boolean wasCapture = target != null && !target.isEmpty();
        if (winConditionRule.isGameOver(target)) {
            isGameOver = true;
            winner = move.getPiece().getColor().name();
            gameOverAt = currentTime;
        }
        Piece arrivedPiece = promotionRule.apply(move.getPiece(), move.getToRow(), board);
        board.setPiece(move.getToRow(), move.getToCol(), arrivedPiece);

        int distance = Math.max(Math.abs(move.getToRow() - move.getFromRow()), Math.abs(move.getToCol() - move.getFromCol()));
        restingRegistry.rest(move.toPosition(), currentTime, distance * GameConfig.LONG_REST_DURATION_MS, PieceVisualState.LONG_REST);

        bus.publish(TOPIC_MOVE_RESOLVED, new MoveEvent(move.getPiece(), new Position(move.getFromRow(), move.getFromCol()),
                move.toPosition(), wasCapture, wasCapture ? target : null, currentTime));
        if (isGameOver) bus.publish(TOPIC_GAME_OVER, new GameOverEvent(winner, currentTime));
    }

    // Pure data snapshot of the current state for the View to render - positions
    // are in the fixed logical grid (GameConfig.CELL_SIZE per cell), never real
    // screen pixels; mapping that onto an actual window is the View's job alone.
    public GameSnapshot snapshot() {
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
