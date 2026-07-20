public class QueenValidator implements MoveValidator {
    private final MoveValidator rook = new RookValidator();
    private final MoveValidator bishop = new BishopValidator();

    @Override
    public boolean isValid(int sr, int sc, int er, int ec, BoardView b) {
        return rook.isValid(sr, sc, er, ec, b) || bishop.isValid(sr, sc, er, ec, b);
    }
}
