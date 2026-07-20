public class KnightValidator implements MoveValidator {
    @Override
    public boolean isValid(int sr, int sc, int er, int ec, BoardView b) {
        int dr = Math.abs(sr - er); int dc = Math.abs(sc - ec);
        boolean isLShape = (dr == 2 && dc == 1) || (dr == 1 && dc == 2);
        return isLShape && PieceValidators.canMoveTo(sr, sc, er, ec, b);
    }
}