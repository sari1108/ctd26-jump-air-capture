// Payload published on GameSession.TOPIC_MOVE_RESOLVED - one move that just finished.
public final class MoveEvent {
    public final Piece piece;
    public final Position from;
    public final Position to;
    public final boolean wasCapture;
    public final Piece capturedPiece;
    public final long time;

    public MoveEvent(Piece piece, Position from, Position to,
                      boolean wasCapture, Piece capturedPiece, long time) {
        this.piece = piece;
        this.from = from;
        this.to = to;
        this.wasCapture = wasCapture;
        this.capturedPiece = capturedPiece;
        this.time = time;
    }
}
