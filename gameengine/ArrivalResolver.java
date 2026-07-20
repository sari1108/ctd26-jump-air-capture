// Resolves a single pending move once it arrives at its destination: either it
// lands normally (capturing whatever's on the square, promoting if eligible) or
// it collides with a piece already airborne over the destination. Owns all the
// board mutation and event publishing for "a move just landed" - GameSession only
// needs to fold the returned Outcome into its own game-over state.
public class ArrivalResolver {
    public static final class Outcome {
        public final boolean gameOver;
        public final String winner;

        private Outcome(boolean gameOver, String winner) {
            this.gameOver = gameOver;
            this.winner = winner;
        }

        static Outcome continuing() { return new Outcome(false, null); }
        static Outcome wonBy(String winner) { return new Outcome(true, winner); }
    }

    private final Board board;
    private final AirborneRegistry airborneRegistry;
    private final RestingRegistry restingRegistry;
    private final PromotionRule promotionRule;
    private final WinConditionRule winConditionRule;
    private final Bus bus;
    private final String moveResolvedTopic;
    private final String gameOverTopic;

    public ArrivalResolver(Board board, AirborneRegistry airborneRegistry, RestingRegistry restingRegistry,
                            PromotionRule promotionRule, WinConditionRule winConditionRule, Bus bus,
                            String moveResolvedTopic, String gameOverTopic) {
        this.board = board;
        this.airborneRegistry = airborneRegistry;
        this.restingRegistry = restingRegistry;
        this.promotionRule = promotionRule;
        this.winConditionRule = winConditionRule;
        this.bus = bus;
        this.moveResolvedTopic = moveResolvedTopic;
        this.gameOverTopic = gameOverTopic;
    }

    public Outcome resolve(PendingMove move, long currentTime) {
        Position targetPos = move.toPosition();

        if (airborneRegistry.isAirborne(targetPos)) {
            AirbornePiece airborneInfo = airborneRegistry.get(targetPos);
            if (move.getPiece().getColor() == airborneInfo.getPiece().getColor()) {
                // Ally already airborne over the destination has right of way; the
                // incoming move never completes and its piece stays on its origin square
                // (which is only ever cleared once we know the move actually resolves).
                return Outcome.continuing();
            }
            board.setPiece(move.getFromRow(), move.getFromCol(), ChessPiece.empty());
            return resolveAirborneCapture(move, targetPos, airborneInfo, currentTime);
        }

        board.setPiece(move.getFromRow(), move.getFromCol(), ChessPiece.empty());
        return resolveNormalArrival(move, currentTime);
    }

    // The piece already airborne has right of way over whatever tries to land on its
    // square while it's still up: it survives and re-occupies the square, and the
    // arriving piece is the one that gets destroyed (it never lands at all).
    private Outcome resolveAirborneCapture(PendingMove move, Position targetPos, AirbornePiece airborneInfo, long currentTime) {
        Piece airbornePiece = airborneInfo.getPiece();
        Piece arrivingPiece = move.getPiece();
        airborneRegistry.resolveCapture(targetPos);

        Outcome outcome = winConditionRule.isGameOver(arrivingPiece)
                ? Outcome.wonBy(airbornePiece.getColor().name())
                : Outcome.continuing();

        board.setPiece(targetPos.getRow(), targetPos.getCol(), airbornePiece);
        restingRegistry.rest(targetPos, currentTime, GameConfig.LONG_REST_DURATION_MS, PieceVisualState.LONG_REST);

        bus.publish(moveResolvedTopic, new MoveEvent(airbornePiece, targetPos, targetPos, true, arrivingPiece, currentTime));
        if (outcome.gameOver) bus.publish(gameOverTopic, new GameOverEvent(outcome.winner, currentTime));
        return outcome;
    }

    private Outcome resolveNormalArrival(PendingMove move, long currentTime) {
        Piece target = board.getPiece(move.getToRow(), move.getToCol());
        boolean wasCapture = target != null && !target.isEmpty();

        Outcome outcome = winConditionRule.isGameOver(target)
                ? Outcome.wonBy(move.getPiece().getColor().name())
                : Outcome.continuing();

        Piece arrivedPiece = promotionRule.apply(move.getPiece(), move.getToRow(), board);
        board.setPiece(move.getToRow(), move.getToCol(), arrivedPiece);

        // Cooldown only applies after capturing (or, separately, after landing from a
        // jump - see AirborneRegistry.landExpired) - a piece that just slid onto an
        // empty square is immediately free to move again.
        if (wasCapture) {
            int distance = Math.max(Math.abs(move.getToRow() - move.getFromRow()), Math.abs(move.getToCol() - move.getFromCol()));
            restingRegistry.rest(move.toPosition(), currentTime, distance * GameConfig.LONG_REST_DURATION_MS, PieceVisualState.LONG_REST);
        }

        bus.publish(moveResolvedTopic, new MoveEvent(move.getPiece(), new Position(move.getFromRow(), move.getFromCol()),
                move.toPosition(), wasCapture, wasCapture ? target : null, currentTime));
        if (outcome.gameOver) bus.publish(gameOverTopic, new GameOverEvent(outcome.winner, currentTime));
        return outcome;
    }
}
