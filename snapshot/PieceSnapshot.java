// Immutable data transfer object: one piece's visual state, as sent from the
// Model to the View. No game logic here, no setters.
public final class PieceSnapshot {
    private final String id;
    private final PieceType type;
    private final PieceColor color;
    private final double pixelX;
    private final double pixelY;
    private final PieceVisualState state;
    private final long stateElapsedMillis;
    private final long stateDurationMillis;

    public PieceSnapshot(String id, PieceType type, PieceColor color,
                          double pixelX, double pixelY,
                          PieceVisualState state, long stateElapsedMillis, long stateDurationMillis) {
        this.id = id;
        this.type = type;
        this.color = color;
        this.pixelX = pixelX;
        this.pixelY = pixelY;
        this.state = state;
        this.stateElapsedMillis = stateElapsedMillis;
        this.stateDurationMillis = stateDurationMillis;
    }

    public String getId() { return id; }
    public PieceType getType() { return type; }
    public PieceColor getColor() { return color; }
    public double getPixelX() { return pixelX; }
    public double getPixelY() { return pixelY; }
    public PieceVisualState getState() { return state; }
    public long getStateElapsedMillis() { return stateElapsedMillis; }
    public long getStateDurationMillis() { return stateDurationMillis; }
}
