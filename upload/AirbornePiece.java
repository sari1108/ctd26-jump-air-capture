public class AirbornePiece {
    private final Piece piece;
    private final long expiryTime;

    public AirbornePiece(Piece piece, long expiryTime) {
        this.piece = piece;
        this.expiryTime = expiryTime;
    }

    public Piece getPiece() { return piece; }
    public long getExpiryTime() { return expiryTime; }
    public boolean hasExpiredBy(long time) { return time >= expiryTime; }
}
