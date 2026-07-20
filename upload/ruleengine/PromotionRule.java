public interface PromotionRule {
    Piece apply(Piece movedPiece, int destRow, Board board);
}
