import java.util.Map;

public class GameSession {
    public static final String TOPIC_MOVE_RESOLVED = "move.resolved";
    public static final String TOPIC_GAME_OVER = "game.over";
    public static final String TOPIC_GAME_STARTED = "game.started";

    private final Board board;
    private final Map<PieceType, MoveValidator> validators;
    private final Bus bus = new Bus();

    private final PendingMoveQueue pendingMoves = new PendingMoveQueue();
    private final AirborneRegistry airborneRegistry = new AirborneRegistry();
    private final RestingRegistry restingRegistry = new RestingRegistry();
    private final PieceSelection selection = new PieceSelection();

    private final ArrivalResolver arrivalResolver;
    private final SnapshotBuilder snapshotBuilder;

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
        this.arrivalResolver = new ArrivalResolver(board, airborneRegistry, restingRegistry,
                promotionRule, winConditionRule, bus, TOPIC_MOVE_RESOLVED, TOPIC_GAME_OVER);
        this.snapshotBuilder = new SnapshotBuilder(board, validators, pendingMoves,
                airborneRegistry, restingRegistry, selection);
    }

    // Pixel-coordinate entry point (kept for the console protocol in Main.java and
    // any other caller that only has raw click coordinates); grid-aware callers
    // should use selectOrMove(row, col) directly instead of multiplying back into pixels.
    public void click(int x, int y) {
        selectOrMove(y / GameConfig.CELL_SIZE, x / GameConfig.CELL_SIZE);
    }

    public void selectOrMove(int r, int c) {
        if (isGameOver) return;
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
            PendingMove resolved = CollisionResolver.resolve(move, pendingMoves.all());
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
            ArrivalResolver.Outcome outcome = arrivalResolver.resolve(move, currentTime);
            if (outcome.gameOver) {
                isGameOver = true;
                winner = outcome.winner;
                gameOverAt = currentTime;
            }
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

    public GameSnapshot snapshot() {
        return snapshotBuilder.build(currentTime, isGameOver, winner, gameOverAt, rejectedPosition, rejectedAt);
    }
}
