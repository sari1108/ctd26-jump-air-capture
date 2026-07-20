public class BoardParser {
    public Board parse(String input) {
        String[] lines = input.trim().split("\n");
        int rows = lines.length;
        int cols = lines[0].trim().split("\\s+").length;
        Board b = new Board(rows, cols);

        for (int i = 0; i < rows; i++) {
            String[] tokens = lines[i].trim().split("\\s+");
            if (tokens.length != cols) throw new IllegalArgumentException("ERROR ROW_WIDTH_MISMATCH");

            for (int j = 0; j < cols; j++) {
                b.setPiece(i, j, parseToken(tokens[j]));
            }
        }
        return b;
    }

    private Piece parseToken(String token) {
        if (token.equals(".")) return ChessPiece.empty();
        if (token.length() != 2) throw new IllegalArgumentException("ERROR UNKNOWN_TOKEN");

        PieceColor color = colorFromPrefix(token.charAt(0));
        PieceType type = typeFromCode(token.charAt(1));
        if (color == null || type == null || type == PieceType.EMPTY) {
            throw new IllegalArgumentException("ERROR UNKNOWN_TOKEN");
        }
        return new ChessPiece(type, color);
    }

    private PieceColor colorFromPrefix(char c) {
        if (c == 'w') return PieceColor.WHITE;
        if (c == 'b') return PieceColor.BLACK;
        return null;
    }

    private PieceType typeFromCode(char c) {
        try {
            return PieceType.fromCode(c);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
