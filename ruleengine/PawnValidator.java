public class PawnValidator implements MoveValidator {
    @Override
    public boolean isValid(int sr, int sc, int er, int ec, BoardView b) {
        Piece p = b.getPieceAt(sr, sc);
        boolean isWhite = (p.getColor() == PieceColor.WHITE);
        int direction = isWhite ? -1 : 1;
        // White's start row scales with board height (2nd from the far edge - row 6
        // on a standard 8-row board). Black's doesn't: mini boards start black at row
        // 0, the standard board at row 1.
        boolean isStartRow = isWhite ? sr == b.getRows() - 2 : (sr == 0 || sr == 1);

        if (sc == ec && er == sr + direction && b.getPieceAt(er, ec).isEmpty()) {
            return true;
        }

        if (isStartRow && sc == ec && er == sr + 2 * direction) {
            if (b.getPieceAt(sr + direction, sc).isEmpty() &&
                    b.getPieceAt(er, ec).isEmpty()) {
                return true;
            }
        }

        if (Math.abs(sc - ec) == 1 && er == sr + direction) {
            Piece target = b.getPieceAt(er, ec);
            return !target.isEmpty() && target.getColor() != p.getColor();
        }
        return false;
    }
}
