public class PieceValidators {
    public static boolean canMoveTo(int r, int c, PieceColor movingColor, BoardView b) {
        Piece target = b.getPieceAt(r, c);
        if (target == null || target.isEmpty()) return true;
        return target.getColor() != movingColor;
    }

    public static boolean canMoveTo(int sr, int sc, int er, int ec, BoardView b) {
        return canMoveTo(er, ec, b.getPieceAt(sr, sc).getColor(), b);
    }

    public static boolean isPathClear(int sr, int sc, int er, int ec, BoardView b) {
        int rStep = Integer.compare(er, sr);
        int cStep = Integer.compare(ec, sc);
        int r = sr + rStep; int c = sc + cStep;
        while (r != er || c != ec) {
            Piece p = b.getPieceAt(r, c);
            if (p != null && !p.isEmpty()) return false;
            r += rStep; c += cStep;
        }
        return true;
    }
}
