public class Board implements BoardView {
    private final Piece[][] grid;

    public Board(int rows, int cols) {
        this.grid = new Piece[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                grid[r][c] = ChessPiece.empty();
            }
        }
    }

    public void setPiece(int r, int c, Piece p) { grid[r][c] = p; }
    public Piece getPiece(int r, int c) { return grid[r][c]; }
    @Override
    public int getRows() { return grid.length; }
    @Override
    public int getCols() { return grid[0].length; }

    @Override
    public Piece getPieceAt(int r, int c) { return grid[r][c]; }
    @Override
    public boolean isWithinBounds(int r, int c) { 
        return r >= 0 && r < grid.length && c >= 0 && c < grid[0].length; 
    }
}