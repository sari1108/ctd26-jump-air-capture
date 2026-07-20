public class RookValidator implements MoveValidator {
    @Override
    public boolean isValid(int sr, int sc, int er, int ec, BoardView b) {
        if (sr != er && sc != ec) return false;
        return PieceValidators.isPathClear(sr, sc, er, ec, b) &&
               PieceValidators.canMoveTo(sr, sc, er, ec, b);
    }
}