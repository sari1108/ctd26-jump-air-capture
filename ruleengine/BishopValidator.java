public class BishopValidator implements MoveValidator {
    @Override
    public boolean isValid(int sr, int sc, int er, int ec, BoardView b) {
        if (Math.abs(sr - er) != Math.abs(sc - ec)) return false;
        return PieceValidators.isPathClear(sr, sc, er, ec, b) &&
               PieceValidators.canMoveTo(sr, sc, er, ec, b);
    }
}
