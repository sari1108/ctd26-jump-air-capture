public class KingValidator implements MoveValidator {
    @Override
    public boolean isValid(int sr, int sc, int er, int ec, BoardView b) {
        return Math.abs(sr - er) <= 1 && Math.abs(sc - ec) <= 1 &&
               PieceValidators.canMoveTo(sr, sc, er, ec, b);
    }
}