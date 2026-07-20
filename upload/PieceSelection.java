public class PieceSelection {
    private Piece piece;
    private Position position;

    public boolean isActive() { return piece != null; }
    public Piece getPiece() { return piece; }
    public Position getPosition() { return position; }

    public void set(Piece piece, Position position) {
        this.piece = piece;
        this.position = position;
    }

    public void clear() {
        piece = null;
        position = null;
    }
}
