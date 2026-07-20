public interface BoardView {
    Piece getPieceAt(int row, int col);
    boolean isWithinBounds(int row, int col);
    int getRows();
    int getCols();
}