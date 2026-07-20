public class QueenValidator implements MoveValidator {
    @Override
    public boolean isValid(int sr, int sc, int er, int ec, BoardView b) {
        boolean isDiagonal = Math.abs(sr - er) == Math.abs(sc - ec);
        boolean isStraight = (sr == er || sc == ec);
        if (!isDiagonal && !isStraight) return false;
        
        return PieceValidators.isPathClear(sr, sc, er, ec, b) &&
               PieceValidators.canMoveTo(sr, sc, er, ec, b);
    }
}