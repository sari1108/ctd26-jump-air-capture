// Serializes the three GameSession bus events (MoveEvent, GameOverEvent,
// GameStartedEvent) to/from single-line wire messages, so a network client can
// re-publish them on its own local Bus and let ScoreTracker/MovesLog/SoundEffects
// react exactly as they do in local play - the bus stays the one mechanism
// driving those side effects whether the game is local or networked.
final class GameEventCodec {
    private GameEventCodec() {
    }

    static String encodeMove(MoveEvent e) {
        Piece captured = e.capturedPiece;
        return "MOVE " + e.piece.getType() + " " + e.piece.getColor() + " "
                + e.from.getRow() + " " + e.from.getCol() + " "
                + e.to.getRow() + " " + e.to.getCol() + " "
                + e.wasCapture + " "
                + (captured == null ? "-" : captured.getType()) + " "
                + (captured == null ? "-" : captured.getColor()) + " "
                + e.time;
    }

    static MoveEvent decodeMove(String text) {
        String[] p = text.split("\\s+");
        Piece piece = new ChessPiece(PieceType.valueOf(p[1]), PieceColor.valueOf(p[2]));
        Position from = new Position(Integer.parseInt(p[3]), Integer.parseInt(p[4]));
        Position to = new Position(Integer.parseInt(p[5]), Integer.parseInt(p[6]));
        boolean wasCapture = Boolean.parseBoolean(p[7]);
        Piece captured = "-".equals(p[8]) ? null : new ChessPiece(PieceType.valueOf(p[8]), PieceColor.valueOf(p[9]));
        long time = Long.parseLong(p[10]);
        return new MoveEvent(piece, from, to, wasCapture, captured, time);
    }

    static String encodeGameOver(GameOverEvent e) {
        return "GAMEOVER " + e.winnerColor + " " + e.time;
    }

    static GameOverEvent decodeGameOver(String text) {
        String[] p = text.split("\\s+");
        return new GameOverEvent(p[1], Long.parseLong(p[2]));
    }

    static String encodeGameStarted(GameStartedEvent e) {
        return "GAMESTARTED " + e.time;
    }

    static GameStartedEvent decodeGameStarted(String text) {
        String[] p = text.split("\\s+");
        return new GameStartedEvent(Long.parseLong(p[1]));
    }
}
