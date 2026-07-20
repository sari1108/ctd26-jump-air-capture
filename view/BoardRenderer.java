import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

// Builds the board (squares, pieces, highlights, overlays) as a single Img each
// frame. Every pixel drawn here goes through Img's own drawing methods - no direct
// Graphics2D/Swing painting in this class.
public class BoardRenderer {
    private static final Color LIGHT_SQUARE = new Color(240, 217, 181);
    private static final Color DARK_SQUARE = new Color(181, 136, 99);
    private static final long START_FADE_MS = 600;
    private static final long GAME_OVER_FADE_MS = 400;

    private final BoardGeometry geometry;
    private final SpriteLibrary sprites;

    public BoardRenderer(int rows, int cols) {
        this.geometry = new BoardGeometry(rows, cols);
        this.sprites = new SpriteLibrary("assets/pieces2");
    }

    public BoardGeometry getGeometry() { return geometry; }

    public Img render(GameSnapshot snapshot, int width, int height) {
        geometry.resize(width, height);
        Img canvas = Img.blank(width, height);

        drawBoard(canvas);
        if (snapshot != null) {
            if (snapshot.getPositionSelected() != null) {
                drawLegalMoves(canvas, snapshot);
                drawSelection(canvas, snapshot.getPositionSelected());
            }
            for (PieceSnapshot piece : snapshot.getPieces()) {
                drawPiece(canvas, piece);
            }
            if (snapshot.getRejectedPosition() != null) {
                drawRejected(canvas, snapshot.getRejectedPosition());
            }
            if (snapshot.isGameOver()) {
                drawGameOver(canvas, snapshot.getWinner(), snapshot.getMsSinceGameOver());
            }
            drawStartFade(canvas, snapshot.getElapsedMillis());
        }
        return canvas;
    }

    // The whole board fades in from black over the first moments of the session -
    // a simple "game start" animation driven by the engine's own clock.
    private void drawStartFade(Img canvas, long elapsedMillis) {
        if (elapsedMillis >= START_FADE_MS) return;
        double t = Math.max(0, Math.min(1, elapsedMillis / (double) START_FADE_MS));
        int alpha = (int) Math.round(255 * (1 - t));
        if (alpha <= 0) return;
        int size = geometry.getBoardSize();
        canvas.fillRect(geometry.getOriginX(), geometry.getOriginY(), size, size, new Color(0, 0, 0, alpha));
    }

    private void drawBoard(Img canvas) {
        for (int r = 0; r < geometry.getRows(); r++) {
            for (int c = 0; c < geometry.getCols(); c++) {
                Rectangle rect = geometry.cellToPixel(r, c);
                canvas.fillRect(rect.x, rect.y, rect.width, rect.height,
                        (r + c) % 2 == 0 ? LIGHT_SQUARE : DARK_SQUARE);
            }
        }
    }

    private void drawSelection(Img canvas, Position pos) {
        Rectangle rect = geometry.cellToPixel(pos.getRow(), pos.getCol());
        int t = 4;
        canvas.fillRect(rect.x, rect.y, rect.width, t, Color.RED);
        canvas.fillRect(rect.x, rect.y + rect.height - t, rect.width, t, Color.RED);
        canvas.fillRect(rect.x, rect.y, t, rect.height, Color.RED);
        canvas.fillRect(rect.x + rect.width - t, rect.y, t, rect.height, Color.RED);
    }

    private void drawLegalMoves(Img canvas, GameSnapshot snapshot) {
        for (Position pos : snapshot.getLegalMoves()) {
            Rectangle rect = geometry.cellToPixel(pos.getRow(), pos.getCol());
            boolean capture = isOccupied(snapshot, pos);
            // Blue/violet, deliberately far from the rest-square's red/amber/green
            // gradient so the two kinds of square tint are never ambiguous.
            canvas.fillRect(rect.x, rect.y, rect.width, rect.height,
                    capture ? new Color(160, 70, 200, 145) : new Color(70, 140, 220, 145));
        }
    }

    private void drawRejected(Img canvas, Position pos) {
        Rectangle rect = geometry.cellToPixel(pos.getRow(), pos.getCol());
        canvas.fillRect(rect.x, rect.y, rect.width, rect.height, new Color(230, 30, 30, 130));
    }

    private boolean isOccupied(GameSnapshot snapshot, Position pos) {
        for (PieceSnapshot p : snapshot.getPieces()) {
            int pr = (int) Math.round(p.getPixelY() / GameConfig.CELL_SIZE);
            int pc = (int) Math.round(p.getPixelX() / GameConfig.CELL_SIZE);
            if (pr == pos.getRow() && pc == pos.getCol()) return true;
        }
        return false;
    }

    // Fades the "game over" banner in over its first moments, instead of it just
    // appearing instantly at full opacity.
    private void drawGameOver(Img canvas, String winnerColor, long msSinceGameOver) {
        double t = Math.max(0, Math.min(1, msSinceGameOver / (double) GAME_OVER_FADE_MS));
        int size = geometry.getBoardSize();
        canvas.fillRect(geometry.getOriginX(), geometry.getOriginY(), size, size,
                new Color(0, 0, 0, (int) Math.round(165 * t)));

        String text = "GAME OVER - " + winnerColor + " WINS";
        float fontScale = Math.max(1.3f, size / 420f);
        int approxCharWidth = Math.round(fontScale * 9.5f);
        int textX = geometry.getOriginX() + Math.max(4, size / 2 - (text.length() * approxCharWidth) / 2);
        int textY = geometry.getOriginY() + size / 2;
        canvas.putText(text, textX, textY, fontScale, new Color(255, 255, 255, (int) Math.round(255 * t)), 1);
    }

    private void drawPiece(Img canvas, PieceSnapshot piece) {
        double cellWidth = geometry.getCellWidth();
        double cellHeight = geometry.getCellHeight();
        double col = piece.getPixelX() / GameConfig.CELL_SIZE;
        double row = piece.getPixelY() / GameConfig.CELL_SIZE;
        int x = geometry.getOriginX() + (int) Math.round(col * cellWidth);
        int y = geometry.getOriginY() + (int) Math.round(row * cellHeight);

        int size = (int) Math.round(Math.min(cellWidth, cellHeight) * 0.85);

        boolean isResting = piece.getState() == PieceVisualState.SHORT_REST || piece.getState() == PieceVisualState.LONG_REST;
        // A slow, continuous "breathing" pulse for any piece just standing there
        // (idle or resting), not actively moving or jumping.
        boolean isBreathing = piece.getState() == PieceVisualState.IDLE || isResting;
        double breathe = 0;
        if (isBreathing) {
            double breathePeriodMs = 700;
            long clock;
            if (piece.getState() == PieceVisualState.IDLE) {
                long phaseOffset = Math.floorMod(piece.getId().hashCode(), (int) breathePeriodMs);
                clock = System.currentTimeMillis() + phaseOffset;
            } else {
                clock = piece.getStateElapsedMillis();
            }
            double phase = (clock % (long) breathePeriodMs) / breathePeriodMs;
            breathe = Math.sin(phase * 2 * Math.PI);
            size += (int) Math.round(size * 0.04 * breathe);
        }

        int drawX = x + (int) Math.round((cellWidth - size) / 2.0);
        int drawY = y + (int) Math.round((cellHeight - size) / 2.0);
        if (isBreathing) {
            drawY -= (int) Math.round(cellHeight * 0.03 * breathe);
        }

        if (piece.getState() == PieceVisualState.JUMP) {
            int shadowWidth = (int) Math.round(size * 0.7);
            int shadowHeight = (int) Math.round(size * 0.18);
            int shadowX = x + (int) Math.round((cellWidth - shadowWidth) / 2.0);
            int shadowY = y + (int) Math.round(cellHeight * 0.82);
            canvas.fillOval(shadowX, shadowY, shadowWidth, shadowHeight, new Color(0, 0, 0, 70));

            double t = Math.max(0, Math.min(1, piece.getStateElapsedMillis() / (double) GameConfig.JUMP_DURATION_MS));
            double bounceHeight = cellHeight * 0.4;
            drawY -= (int) Math.round(bounceHeight * Math.sin(Math.PI * t));
        }

        long restDuration = piece.getStateDurationMillis();
        if (isResting && restDuration > 0) {
            double remaining = 1.0 - piece.getStateElapsedMillis() / (double) restDuration;
            drawRestSquare(canvas, x, y, (int) Math.round(cellWidth), (int) Math.round(cellHeight), remaining);
        }

        BufferedImage frame = sprites.frameFor(piece.getType(), piece.getColor(), piece.getState(),
                piece.getStateElapsedMillis());
        if (frame != null) {
            Img sprite = Img.of(frame);
            if (sprite.getWidth() != size || sprite.getHeight() != size) {
                sprite = sprite.scaledCopy(size, size);
            }
            int safeX = Math.max(0, Math.min(drawX, canvas.getWidth() - sprite.getWidth()));
            int safeY = Math.max(0, Math.min(drawY, canvas.getHeight() - sprite.getHeight()));
            sprite.drawOn(canvas, safeX, safeY);
        }
    }

    private void drawRestSquare(Img canvas, int cellX, int cellY, int cellWidth, int cellHeight, double remaining) {
        remaining = Math.max(0, Math.min(1, remaining));
        int filledHeight = (int) Math.round(cellHeight * remaining);
        if (filledHeight <= 0) return;
        Color c = restColor(remaining);
        canvas.fillRect(cellX, cellY + (cellHeight - filledHeight), cellWidth, filledHeight,
                new Color(c.getRed(), c.getGreen(), c.getBlue(), 160));
    }

    // remainingFraction: 1.0 = just rested (red) -> 0.5 (amber) -> 0.0 = ready (green).
    private Color restColor(double remainingFraction) {
        double readyFraction = Math.max(0, Math.min(1, 1 - remainingFraction));
        Color start;
        Color end;
        double t;
        if (readyFraction < 0.5) {
            start = new Color(219, 68, 55);
            end = new Color(240, 173, 40);
            t = readyFraction / 0.5;
        } else {
            start = new Color(240, 173, 40);
            end = new Color(52, 168, 83);
            t = (readyFraction - 0.5) / 0.5;
        }
        int r = (int) Math.round(start.getRed() + (end.getRed() - start.getRed()) * t);
        int g = (int) Math.round(start.getGreen() + (end.getGreen() - start.getGreen()) * t);
        int b = (int) Math.round(start.getBlue() + (end.getBlue() - start.getBlue()) * t);
        return new Color(r, g, b);
    }
}
