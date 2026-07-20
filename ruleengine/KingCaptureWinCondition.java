public class KingCaptureWinCondition implements WinConditionRule {
    @Override
    public boolean isGameOver(Piece capturedPiece) {
        return capturedPiece != null && !capturedPiece.isEmpty() && capturedPiece.getType() == PieceType.KING;
    }
}
