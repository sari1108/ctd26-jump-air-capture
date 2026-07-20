public class PendingMove {
    private final Piece piece;
    private final int fromRow, fromCol, toRow, toCol;
    private final long startTime;
    private final long arrivalTime;

    public PendingMove(Piece piece, int fromRow, int fromCol, int toRow, int toCol, long startTime, long arrivalTime) {
        this.piece = piece;
        this.fromRow = fromRow;
        this.fromCol = fromCol;
        this.toRow = toRow;
        this.toCol = toCol;
        this.startTime = startTime;
        this.arrivalTime = arrivalTime;
    }

    public Piece getPiece() { return piece; }
    public int getFromRow() { return fromRow; }
    public int getFromCol() { return fromCol; }
    public int getToRow() { return toRow; }
    public int getToCol() { return toCol; }
    public long getStartTime() { return startTime; }
    public long getArrivalTime() { return arrivalTime; }

    public boolean hasArrivedBy(long time) { return time >= arrivalTime; }
    public Position toPosition() { return new Position(toRow, toCol); }

    public PendingMove truncateTo(int row, int col, long arrivalTime) {
        return new PendingMove(piece, fromRow, fromCol, row, col, startTime, arrivalTime);
    }
}
