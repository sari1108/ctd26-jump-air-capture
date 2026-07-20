public enum PieceType {
    EMPTY('.'), ROOK('R'), KNIGHT('N'), BISHOP('B'), QUEEN('Q'), KING('K'), PAWN('P');

    private final char code;

    PieceType(char code) { this.code = code; }

    public char getCode() { return code; }

    public static PieceType fromCode(char code) {
        for (PieceType type : values()) {
            if (type.code == code) return type;
        }
        throw new IllegalArgumentException("Unknown piece code: " + code);
    }
}
