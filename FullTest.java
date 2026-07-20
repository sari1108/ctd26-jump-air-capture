import java.util.ArrayList;
import java.util.List;

public class FullTest {
    static int pass;
    static int fail;

    public static void main(String[] args) {
        testRookStraightMove();
        testRookBlockedByOwnPiece();
        testRookCapturesAdjacentEnemy();
        testRookCannotJumpOverEnemyMidPath();
        testRookCannotMoveDiagonally();

        testBishopDiagonalMove();
        testBishopBlockedOnDiagonal();
        testBishopCannotMoveStraight();

        testQueenStraightMove();
        testQueenDiagonalMove();
        testQueenCannotMoveLShape();
        testQueenBlockedLikeRookOnStraightPath();
        testQueenBlockedLikeBishopOnDiagonalPath();

        testKnightJumpsOverPieces();
        testKnightIllegalShapeRejected();

        testKingOneSquareMove();
        testKingCannotMoveTwoSquares();

        testPawnSingleForward();
        testPawnDoubleFromStartRow_White();
        testPawnDoubleFromStartRow_Black_StandardBoard();
        testPawnDoubleFromStartRow_Black_MiniBoard();
        testPawnCannotDoubleFromNonStartRow();
        testPawnCannotMoveDiagonalWithoutCapture();
        testPawnCannotCaptureStraightAhead();
        testPawnCapturesDiagonally();
        testPawnPromotionOnLastRow();

        testCollision_SameColorBlocksOneCellShort();
        testCollision_DifferentColorCancelsIncomingMove();

        testClickOutsideBoardIsIgnored();
        testClickEmptySquareWithNoSelectionIsNoop();
        testReselectDifferentOwnPieceBeforeMoving();
        testNoCooldownAfterNonCapturingArrival();
        testCooldownAppliesAfterCapture();
        testRestDurationScalesWithCaptureDistance();
        testPromotedPieceCanMoveAgainImmediately();
        testClickAndSelectOrMoveProduceIdenticalResult();

        testAirborneCapture_DifferentColor_ArrivingPieceDestroyedAirborneSurvives();
        testAirborneLanding_SameColor_MoveAbortsPieceStaysAtOrigin();

        System.out.println("\n==== " + pass + " passed, " + fail + " failed ====");
        System.exit(fail > 0 ? 1 : 0);
    }

    // ---------- rook ----------

    static void testRookStraightMove() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.ROOK, PieceColor.WHITE);
        check("rook moves straight along an empty row", new RookValidator().isValid(4, 4, 4, 7, b));
        check("rook moves straight along an empty column", new RookValidator().isValid(4, 4, 1, 4, b));
    }

    static void testRookBlockedByOwnPiece() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.ROOK, PieceColor.WHITE);
        place(b, 4, 6, PieceType.PAWN, PieceColor.WHITE);
        check("rook cannot pass through its own piece", !new RookValidator().isValid(4, 4, 4, 7, b));
        check("rook cannot land on its own piece", !new RookValidator().isValid(4, 4, 4, 6, b));
    }

    static void testRookCapturesAdjacentEnemy() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.ROOK, PieceColor.WHITE);
        place(b, 4, 5, PieceType.PAWN, PieceColor.BLACK);
        check("rook captures an adjacent enemy", new RookValidator().isValid(4, 4, 4, 5, b));
    }

    static void testRookCannotJumpOverEnemyMidPath() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.ROOK, PieceColor.WHITE);
        place(b, 4, 5, PieceType.PAWN, PieceColor.BLACK);
        check("rook cannot jump over an enemy mid-path", !new RookValidator().isValid(4, 4, 4, 6, b));
    }

    static void testRookCannotMoveDiagonally() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.ROOK, PieceColor.WHITE);
        check("rook cannot move diagonally", !new RookValidator().isValid(4, 4, 6, 6, b));
    }

    // ---------- bishop ----------

    static void testBishopDiagonalMove() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.BISHOP, PieceColor.WHITE);
        check("bishop moves diagonally", new BishopValidator().isValid(4, 4, 6, 6, b));
    }

    static void testBishopBlockedOnDiagonal() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.BISHOP, PieceColor.WHITE);
        place(b, 5, 5, PieceType.PAWN, PieceColor.WHITE);
        check("bishop cannot pass through a piece on its diagonal", !new BishopValidator().isValid(4, 4, 6, 6, b));
    }

    static void testBishopCannotMoveStraight() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.BISHOP, PieceColor.WHITE);
        check("bishop cannot move straight", !new BishopValidator().isValid(4, 4, 4, 7, b));
    }

    // ---------- queen (regression: QueenValidator now delegates to Rook+Bishop) ----------

    static void testQueenStraightMove() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.QUEEN, PieceColor.WHITE);
        check("queen moves straight", new QueenValidator().isValid(4, 4, 4, 7, b));
    }

    static void testQueenDiagonalMove() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.QUEEN, PieceColor.WHITE);
        check("queen moves diagonally", new QueenValidator().isValid(4, 4, 6, 6, b));
    }

    static void testQueenCannotMoveLShape() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.QUEEN, PieceColor.WHITE);
        check("queen cannot move in an L shape", !new QueenValidator().isValid(4, 4, 6, 5, b));
    }

    static void testQueenBlockedLikeRookOnStraightPath() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.QUEEN, PieceColor.WHITE);
        place(b, 4, 5, PieceType.PAWN, PieceColor.WHITE);
        check("queen's straight path is blocked exactly like a rook's",
                !new QueenValidator().isValid(4, 4, 4, 7, b));
    }

    static void testQueenBlockedLikeBishopOnDiagonalPath() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.QUEEN, PieceColor.WHITE);
        place(b, 5, 5, PieceType.PAWN, PieceColor.WHITE);
        check("queen's diagonal path is blocked exactly like a bishop's",
                !new QueenValidator().isValid(4, 4, 6, 6, b));
    }

    // ---------- knight ----------

    static void testKnightJumpsOverPieces() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.KNIGHT, PieceColor.WHITE);
        place(b, 3, 4, PieceType.PAWN, PieceColor.WHITE);
        place(b, 2, 4, PieceType.PAWN, PieceColor.WHITE);
        check("knight jumps over pieces in its path", new KnightValidator().isValid(4, 4, 2, 5, b));
    }

    static void testKnightIllegalShapeRejected() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.KNIGHT, PieceColor.WHITE);
        check("knight rejects a non-L-shaped move", !new KnightValidator().isValid(4, 4, 5, 5, b));
    }

    // ---------- king ----------

    static void testKingOneSquareMove() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.KING, PieceColor.WHITE);
        check("king moves one square", new KingValidator().isValid(4, 4, 4, 5, b));
    }

    static void testKingCannotMoveTwoSquares() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.KING, PieceColor.WHITE);
        check("king cannot move two squares", !new KingValidator().isValid(4, 4, 4, 6, b));
    }

    // ---------- pawn (regression: PawnValidator's start-row check was simplified) ----------

    static void testPawnSingleForward() {
        Board b = new Board(8, 8);
        place(b, 6, 4, PieceType.PAWN, PieceColor.WHITE);
        check("white pawn moves one square forward", new PawnValidator().isValid(6, 4, 5, 4, b));
    }

    static void testPawnDoubleFromStartRow_White() {
        Board b = new Board(8, 8);
        place(b, 6, 4, PieceType.PAWN, PieceColor.WHITE);
        check("white pawn double-steps from its start row (row 6 of 8)",
                new PawnValidator().isValid(6, 4, 4, 4, b));
    }

    static void testPawnDoubleFromStartRow_Black_StandardBoard() {
        Board b = new Board(8, 8);
        place(b, 1, 4, PieceType.PAWN, PieceColor.BLACK);
        check("black pawn double-steps from row 1 on a standard 8-row board",
                new PawnValidator().isValid(1, 4, 3, 4, b));
    }

    static void testPawnDoubleFromStartRow_Black_MiniBoard() {
        Board b = new Board(5, 5);
        place(b, 0, 2, PieceType.PAWN, PieceColor.BLACK);
        check("black pawn double-steps from row 0 on a mini board",
                new PawnValidator().isValid(0, 2, 2, 2, b));
    }

    static void testPawnCannotDoubleFromNonStartRow() {
        Board b = new Board(8, 8);
        place(b, 5, 4, PieceType.PAWN, PieceColor.WHITE);
        check("white pawn cannot double-step outside its start row",
                !new PawnValidator().isValid(5, 4, 3, 4, b));
    }

    static void testPawnCannotMoveDiagonalWithoutCapture() {
        Board b = new Board(8, 8);
        place(b, 6, 4, PieceType.PAWN, PieceColor.WHITE);
        check("pawn cannot move diagonally onto an empty square",
                !new PawnValidator().isValid(6, 4, 5, 5, b));
    }

    static void testPawnCannotCaptureStraightAhead() {
        Board b = new Board(8, 8);
        place(b, 6, 4, PieceType.PAWN, PieceColor.WHITE);
        place(b, 5, 4, PieceType.PAWN, PieceColor.BLACK);
        check("pawn cannot capture straight ahead", !new PawnValidator().isValid(6, 4, 5, 4, b));
    }

    static void testPawnCapturesDiagonally() {
        Board b = new Board(8, 8);
        place(b, 6, 4, PieceType.PAWN, PieceColor.WHITE);
        place(b, 5, 5, PieceType.PAWN, PieceColor.BLACK);
        check("pawn captures diagonally", new PawnValidator().isValid(6, 4, 5, 5, b));
    }

    static void testPawnPromotionOnLastRow() {
        Board b = new Board(8, 8);
        Piece pawn = new ChessPiece(PieceType.PAWN, PieceColor.WHITE);
        Piece promoted = new StandardPromotionRule().apply(pawn, 0, b);
        check("pawn promotes to a queen on the last row", promoted.getType() == PieceType.QUEEN);
        Piece midBoard = new StandardPromotionRule().apply(pawn, 4, b);
        check("pawn does not promote away from the last row", midBoard.getType() == PieceType.PAWN);
    }

    // ---------- collision resolution (regression: CollisionResolver extracted out of GameSession) ----------

    static void testCollision_SameColorBlocksOneCellShort() {
        Piece piece = new ChessPiece(PieceType.ROOK, PieceColor.WHITE);
        PendingMove inFlight = new PendingMove(piece, 0, 0, 0, 2, 0, 2000);
        PendingMove incoming = new PendingMove(piece, 0, 5, 0, 0, 0, 5000);
        PendingMove resolved = CollisionResolver.resolve(incoming, List.of(inFlight));
        check("same-color collision stops the incoming move one cell short of the shared square",
                resolved != null && resolved.getToRow() == 0 && resolved.getToCol() == 3);
    }

    static void testCollision_DifferentColorCancelsIncomingMove() {
        Piece white = new ChessPiece(PieceType.ROOK, PieceColor.WHITE);
        Piece black = new ChessPiece(PieceType.ROOK, PieceColor.BLACK);
        PendingMove inFlight = new PendingMove(black, 0, 0, 0, 2, 0, 2000);
        PendingMove incoming = new PendingMove(white, 0, 5, 0, 0, 0, 5000);
        PendingMove resolved = CollisionResolver.resolve(incoming, List.of(inFlight));
        check("different-color collision cancels the incoming move entirely", resolved == null);
    }

    // ---------- GameSession / public API ----------

    static void testClickOutsideBoardIsIgnored() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.ROOK, PieceColor.WHITE);
        GameSession session = new GameSession(b);
        session.selectOrMove(4, 4);
        session.selectOrMove(100, 100);
        Position selected = session.getSelectedPosition();
        check("selecting a square outside the board is ignored and the current selection survives",
                selected != null && selected.getRow() == 4 && selected.getCol() == 4);
    }

    static void testClickEmptySquareWithNoSelectionIsNoop() {
        Board b = new Board(8, 8);
        GameSession session = new GameSession(b);
        session.selectOrMove(3, 3);
        check("clicking an empty square with nothing selected starts no selection",
                session.getSelectedPosition() == null);
    }

    static void testReselectDifferentOwnPieceBeforeMoving() {
        Board b = new Board(8, 8);
        place(b, 4, 4, PieceType.ROOK, PieceColor.WHITE);
        place(b, 6, 6, PieceType.ROOK, PieceColor.WHITE);
        GameSession session = new GameSession(b);
        session.selectOrMove(4, 4);
        session.selectOrMove(6, 6);
        Position selected = session.getSelectedPosition();
        check("clicking a different own piece re-selects it instead of attempting an illegal move",
                selected != null && selected.getRow() == 6 && selected.getCol() == 6);
    }

    // Regression test for a bug the grading site caught: RestingRegistry's own
    // doc comment says cooldown applies "after capturing, or after landing from
    // a jump" - but resolveNormalArrival used to call rest() unconditionally,
    // locking out a piece that had simply slid onto an empty square.
    static void testNoCooldownAfterNonCapturingArrival() {
        Board b = new Board(1, 3);
        place(b, 0, 0, PieceType.ROOK, PieceColor.WHITE);
        GameSession session = new GameSession(b);
        session.selectOrMove(0, 0);
        session.selectOrMove(0, 1);
        session.waitMs(1000);
        session.selectOrMove(0, 1);
        session.selectOrMove(0, 2);
        session.waitMs(1000);
        check("a piece that lands on an empty square can move again immediately, with no cooldown",
                symbolAt(b, 0, 2, "wR") && symbolAt(b, 0, 1, "."));
    }

    static void testCooldownAppliesAfterCapture() {
        Board b = new Board(1, 3);
        place(b, 0, 0, PieceType.ROOK, PieceColor.WHITE);
        place(b, 0, 1, PieceType.PAWN, PieceColor.BLACK);
        GameSession session = new GameSession(b);
        session.selectOrMove(0, 0);
        session.selectOrMove(0, 1);
        session.waitMs(1000);
        session.selectOrMove(0, 1);
        check("a piece that just captured cannot be reselected immediately (resting)",
                session.getSelectedPosition() == null);
        session.waitMs(1001);
        session.selectOrMove(0, 1);
        check("a piece can be reselected once its post-capture rest has expired",
                session.getSelectedPosition() != null);
    }

    static void testRestDurationScalesWithCaptureDistance() {
        Board b1 = new Board(8, 8);
        Piece piece1 = new ChessPiece(PieceType.ROOK, PieceColor.WHITE);
        b1.setPiece(4, 4, piece1);
        b1.setPiece(4, 5, new ChessPiece(PieceType.PAWN, PieceColor.BLACK));
        RestingRegistry resting1 = new RestingRegistry();
        ArrivalResolver resolver1 = new ArrivalResolver(b1, new AirborneRegistry(), resting1,
                new StandardPromotionRule(), new KingCaptureWinCondition(), new Bus(), "move.resolved", "game.over");
        resolver1.resolve(new PendingMove(piece1, 4, 4, 4, 5, 0, 1000), 1000);
        long oneCellRest = resting1.durationOf(new Position(4, 5));

        Board b2 = new Board(8, 8);
        Piece piece2 = new ChessPiece(PieceType.ROOK, PieceColor.WHITE);
        b2.setPiece(4, 4, piece2);
        b2.setPiece(4, 7, new ChessPiece(PieceType.PAWN, PieceColor.BLACK));
        RestingRegistry resting2 = new RestingRegistry();
        ArrivalResolver resolver2 = new ArrivalResolver(b2, new AirborneRegistry(), resting2,
                new StandardPromotionRule(), new KingCaptureWinCondition(), new Bus(), "move.resolved", "game.over");
        resolver2.resolve(new PendingMove(piece2, 4, 4, 4, 7, 0, 3000), 3000);
        long threeCellRest = resting2.durationOf(new Position(4, 7));

        check("rest duration after a capture scales with the distance just traveled",
                oneCellRest > 0 && threeCellRest == 3 * oneCellRest);
    }

    // Regression test mirroring the grading site's promoted_queen_moves_diagonal case.
    static void testPromotedPieceCanMoveAgainImmediately() {
        Board b = new Board(3, 3);
        place(b, 1, 1, PieceType.PAWN, PieceColor.WHITE);
        GameSession session = new GameSession(b);
        session.selectOrMove(1, 1);
        session.selectOrMove(0, 1);
        session.waitMs(1000);
        session.selectOrMove(0, 1);
        session.selectOrMove(1, 2);
        session.waitMs(1000);
        check("a pawn that just promoted can move again immediately as its new piece type",
                symbolAt(b, 1, 2, "wQ") && symbolAt(b, 0, 1, "."));
    }

    static void testClickAndSelectOrMoveProduceIdenticalResult() {
        Board bViaClick = new Board(8, 8);
        place(bViaClick, 6, 4, PieceType.PAWN, PieceColor.WHITE);
        GameSession sessionViaClick = new GameSession(bViaClick);
        sessionViaClick.click(4 * GameConfig.CELL_SIZE, 6 * GameConfig.CELL_SIZE);
        sessionViaClick.click(4 * GameConfig.CELL_SIZE, 4 * GameConfig.CELL_SIZE);
        sessionViaClick.waitMs(3000);

        Board bViaGrid = new Board(8, 8);
        place(bViaGrid, 6, 4, PieceType.PAWN, PieceColor.WHITE);
        GameSession sessionViaGrid = new GameSession(bViaGrid);
        sessionViaGrid.selectOrMove(6, 4);
        sessionViaGrid.selectOrMove(4, 4);
        sessionViaGrid.waitMs(3000);

        check("pixel-based click(x,y) moves the pawn the same way selectOrMove(row,col) does",
                symbolAt(bViaClick, 4, 4, "wP") && symbolAt(bViaClick, 6, 4, ".")
                        && symbolAt(bViaGrid, 4, 4, "wP") && symbolAt(bViaGrid, 6, 4, "."));
    }

    // ---------- airborne collision (ArrivalResolver) ----------

    static void testAirborneCapture_DifferentColor_ArrivingPieceDestroyedAirborneSurvives() {
        Board board = new Board(8, 8);
        Piece airborneRook = new ChessPiece(PieceType.ROOK, PieceColor.BLACK);
        Piece arrivingRook = new ChessPiece(PieceType.ROOK, PieceColor.WHITE);
        board.setPiece(4, 4, arrivingRook);

        AirborneRegistry airborneRegistry = new AirborneRegistry();
        airborneRegistry.activate(new Position(4, 6), airborneRook, 5000);

        List<Object> published = new ArrayList<>();
        Bus bus = new Bus();
        bus.subscribe("move.resolved", published::add);

        ArrivalResolver resolver = new ArrivalResolver(board, airborneRegistry, new RestingRegistry(),
                new StandardPromotionRule(), new KingCaptureWinCondition(), bus, "move.resolved", "game.over");
        ArrivalResolver.Outcome outcome = resolver.resolve(new PendingMove(arrivingRook, 4, 4, 4, 6, 0, 2000), 2000);

        check("different-color arrival on an airborne square: the airborne piece survives and reoccupies it",
                symbolAt(board, 4, 6, "bR"));
        check("different-color arrival on an airborne square: the arriving piece is not placed anywhere",
                symbolAt(board, 4, 4, "."));
        check("different-color airborne collision publishes a move-resolved event", published.size() == 1);
        check("different-color airborne collision here does not end the game", !outcome.gameOver);
    }

    // Regression test for the fixed bug: an ally already airborne over the destination
    // used to leave the arriving piece nowhere on the board (cleared from its origin,
    // never placed anywhere) because only the different-color branch was handled.
    static void testAirborneLanding_SameColor_MoveAbortsPieceStaysAtOrigin() {
        Board board = new Board(8, 8);
        Piece airborneRook = new ChessPiece(PieceType.ROOK, PieceColor.WHITE);
        Piece arrivingRook = new ChessPiece(PieceType.ROOK, PieceColor.WHITE);
        board.setPiece(4, 4, arrivingRook);

        AirborneRegistry airborneRegistry = new AirborneRegistry();
        airborneRegistry.activate(new Position(4, 6), airborneRook, 5000);

        List<Object> published = new ArrayList<>();
        Bus bus = new Bus();
        bus.subscribe("move.resolved", published::add);
        bus.subscribe("game.over", published::add);

        ArrivalResolver resolver = new ArrivalResolver(board, airborneRegistry, new RestingRegistry(),
                new StandardPromotionRule(), new KingCaptureWinCondition(), bus, "move.resolved", "game.over");
        ArrivalResolver.Outcome outcome = resolver.resolve(new PendingMove(arrivingRook, 4, 4, 4, 6, 0, 2000), 2000);

        check("same-color airborne landing: the arriving piece stays on its origin square instead of vanishing",
                symbolAt(board, 4, 4, "wR"));
        check("same-color airborne landing: the destination is left empty for the airborne piece to land on",
                symbolAt(board, 4, 6, "."));
        check("same-color airborne landing: the ally is still registered as airborne, untouched",
                airborneRegistry.isAirborne(new Position(4, 6)));
        check("same-color airborne landing publishes no event for the aborted move", published.isEmpty());
        check("same-color airborne landing never ends the game", !outcome.gameOver);
    }

    // ---------- helpers ----------

    static void place(Board b, int r, int c, PieceType type, PieceColor color) {
        b.setPiece(r, c, new ChessPiece(type, color));
    }

    static boolean symbolAt(Board b, int r, int c, String expected) {
        return b.getPiece(r, c).getSymbol().equals(expected);
    }

    static void check(String name, boolean condition) {
        if (condition) {
            pass++;
            System.out.println("PASS: " + name);
        } else {
            fail++;
            System.out.println("FAIL: " + name);
        }
    }
}
