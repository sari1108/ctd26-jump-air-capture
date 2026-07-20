public class StandardPromotionRule implements PromotionRule {
    @Override
    public Piece apply(Piece movedPiece, int destRow, Board board) {
        if (movedPiece.getType() != PieceType.PAWN) return movedPiece;
        boolean atLastRow = (destRow == 0 || destRow == board.getRows() - 1);
        if (!atLastRow) return movedPiece;
        return new ChessPiece(PieceType.QUEEN, movedPiece.getColor());
    }
}
