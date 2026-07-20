public class ChessPiece implements Piece {
    private final PieceType type;
    private final PieceColor color;

    public ChessPiece(PieceType type, PieceColor color) {
        this.type = type;
        this.color = color;
    }

    public static ChessPiece empty() {
        return new ChessPiece(PieceType.EMPTY, PieceColor.NONE);
    }

    @Override
    public PieceType getType() { return type; }

    @Override
    public PieceColor getColor() { return color; }

    @Override
    public boolean isEmpty() { return type == PieceType.EMPTY; }

    @Override
    public String getSymbol() {
        if (isEmpty()) return ".";
        return (color == PieceColor.WHITE ? "w" : "b") + type.getCode();
    }
}
