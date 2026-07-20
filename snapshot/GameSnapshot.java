import java.util.List;

// Immutable data transfer object: the whole board state, as sent from the
// Model to the View on every render. No game logic here, no setters.
public final class GameSnapshot {
    private final int boardRows;
    private final int boardCols;
    private final List<PieceSnapshot> pieces;
    private final Position positionSelected;
    private final Position rejectedPosition;
    private final List<Position> legalMoves;
    private final boolean gameOver;
    private final String winner;
    private final long elapsedMillis;
    private final long msSinceGameOver;

    public GameSnapshot(int boardRows, int boardCols, List<PieceSnapshot> pieces,
                         Position positionSelected, Position rejectedPosition, List<Position> legalMoves,
                         boolean gameOver, String winner, long elapsedMillis, long msSinceGameOver) {
        this.boardRows = boardRows;
        this.boardCols = boardCols;
        this.pieces = List.copyOf(pieces);
        this.positionSelected = positionSelected;
        this.rejectedPosition = rejectedPosition;
        this.legalMoves = List.copyOf(legalMoves);
        this.gameOver = gameOver;
        this.winner = winner;
        this.elapsedMillis = elapsedMillis;
        this.msSinceGameOver = msSinceGameOver;
    }

    public int getBoardRows() { return boardRows; }
    public int getBoardCols() { return boardCols; }
    public List<PieceSnapshot> getPieces() { return pieces; }
    public Position getPositionSelected() { return positionSelected; }
    public Position getRejectedPosition() { return rejectedPosition; }
    public List<Position> getLegalMoves() { return legalMoves; }
    public boolean isGameOver() { return gameOver; }
    public String getWinner() { return winner; }
    public long getElapsedMillis() { return elapsedMillis; }
    public long getMsSinceGameOver() { return msSinceGameOver; }
}
