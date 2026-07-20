import java.util.ArrayList;
import java.util.List;

// Serializes GameSnapshot to/from a compact line-based text format for the
// WebSocket wire - no JSON library available, so plain delimited text, in
// the same spirit as the project's existing hand-rolled sprite-config reader.
final class GameSnapshotCodec {
    private GameSnapshotCodec() {
    }

    static String encode(GameSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("GAMESTATE\n");
        sb.append(snapshot.getBoardRows()).append(',').append(snapshot.getBoardCols()).append('\n');
        sb.append(snapshot.isGameOver()).append(',')
                .append(snapshot.getWinner() == null ? "-" : snapshot.getWinner()).append(',')
                .append(snapshot.getElapsedMillis()).append(',')
                .append(snapshot.getMsSinceGameOver()).append('\n');
        appendPosition(sb, snapshot.getPositionSelected());
        appendPosition(sb, snapshot.getRejectedPosition());

        List<Position> legalMoves = snapshot.getLegalMoves();
        sb.append(legalMoves.size()).append('\n');
        for (Position p : legalMoves) {
            sb.append(p.getRow()).append(',').append(p.getCol()).append('\n');
        }

        List<PieceSnapshot> pieces = snapshot.getPieces();
        sb.append(pieces.size()).append('\n');
        for (PieceSnapshot piece : pieces) {
            sb.append(piece.getId()).append('|')
                    .append(piece.getType()).append('|')
                    .append(piece.getColor()).append('|')
                    .append(piece.getPixelX()).append('|')
                    .append(piece.getPixelY()).append('|')
                    .append(piece.getState()).append('|')
                    .append(piece.getStateElapsedMillis()).append('|')
                    .append(piece.getStateDurationMillis()).append('\n');
        }
        return sb.toString();
    }

    static GameSnapshot decode(String text) {
        String[] lines = text.split("\n", -1);
        int i = 0;
        if (!lines[i].equals("GAMESTATE")) {
            throw new IllegalArgumentException("Not a GAMESTATE payload");
        }
        i++;

        String[] dims = lines[i++].split(",");
        int rows = Integer.parseInt(dims[0]);
        int cols = Integer.parseInt(dims[1]);

        String[] meta = lines[i++].split(",", -1);
        boolean gameOver = Boolean.parseBoolean(meta[0]);
        String winner = meta[1].equals("-") ? null : meta[1];
        long elapsedMillis = Long.parseLong(meta[2]);
        long msSinceGameOver = Long.parseLong(meta[3]);

        Position selected = readPosition(lines[i++]);
        Position rejected = readPosition(lines[i++]);

        int legalCount = Integer.parseInt(lines[i++]);
        List<Position> legalMoves = new ArrayList<>(legalCount);
        for (int n = 0; n < legalCount; n++) {
            String[] rc = lines[i++].split(",");
            legalMoves.add(new Position(Integer.parseInt(rc[0]), Integer.parseInt(rc[1])));
        }

        int pieceCount = Integer.parseInt(lines[i++]);
        List<PieceSnapshot> pieces = new ArrayList<>(pieceCount);
        for (int n = 0; n < pieceCount; n++) {
            String[] f = lines[i++].split("\\|", -1);
            pieces.add(new PieceSnapshot(
                    f[0],
                    PieceType.valueOf(f[1]),
                    PieceColor.valueOf(f[2]),
                    Double.parseDouble(f[3]),
                    Double.parseDouble(f[4]),
                    PieceVisualState.valueOf(f[5]),
                    Long.parseLong(f[6]),
                    Long.parseLong(f[7])));
        }

        return new GameSnapshot(rows, cols, pieces, selected, rejected, legalMoves, gameOver, winner, elapsedMillis, msSinceGameOver);
    }

    private static void appendPosition(StringBuilder sb, Position p) {
        if (p == null) {
            sb.append("-1,-1\n");
        } else {
            sb.append(p.getRow()).append(',').append(p.getCol()).append('\n');
        }
    }

    private static Position readPosition(String line) {
        String[] rc = line.split(",");
        int r = Integer.parseInt(rc[0]);
        int c = Integer.parseInt(rc[1]);
        return r == -1 && c == -1 ? null : new Position(r, c);
    }
}
