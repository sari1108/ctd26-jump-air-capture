import java.awt.Rectangle;

// Maps between board cells (row/col) and on-screen pixels for a square board
// rendered inside a panel of any size. Call resize() whenever the panel's
// width/height changes (e.g. on window resize) before converting coordinates.
public class BoardGeometry {
    private final int rows;
    private final int cols;
    private int boardSize;
    private int originX;
    private int originY;

    public BoardGeometry(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
    }

    public void resize(int panelWidth, int panelHeight) {
        boardSize = Math.min(panelWidth, panelHeight);
        originX = (panelWidth - boardSize) / 2;
        originY = (panelHeight - boardSize) / 2;
    }

    public int getRows() { return rows; }
    public int getCols() { return cols; }
    public int getBoardSize() { return boardSize; }
    public int getOriginX() { return originX; }
    public int getOriginY() { return originY; }
    public double getCellWidth() { return (double) boardSize / cols; }
    public double getCellHeight() { return (double) boardSize / rows; }

    // Pixel rectangle (relative to the panel) that a given cell occupies.
    public Rectangle cellToPixel(int row, int col) {
        double cw = getCellWidth();
        double ch = getCellHeight();
        int x = originX + (int) Math.round(col * cw);
        int y = originY + (int) Math.round(row * ch);
        int w = originX + (int) Math.round((col + 1) * cw) - x;
        int h = originY + (int) Math.round((row + 1) * ch) - y;
        return new Rectangle(x, y, w, h);
    }

    // Cell under a given pixel (relative to the panel), or null if outside the board.
    public int[] pixelToCell(int px, int py) {
        int localX = px - originX;
        int localY = py - originY;
        if (localX < 0 || localY < 0 || localX >= boardSize || localY >= boardSize) {
            return null;
        }
        int col = Math.min((int) (localX / getCellWidth()), cols - 1);
        int row = Math.min((int) (localY / getCellHeight()), rows - 1);
        return new int[] { row, col };
    }
}
