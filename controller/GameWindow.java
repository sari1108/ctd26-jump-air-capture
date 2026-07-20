import java.awt.BorderLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

// The only Swing this game uses beyond a plain pixel surface: one JFrame holding
// one lightweight component that displays a single composited Img, rebuilt every
// tick. All actual drawing (board, pieces, highlights, names/score/moves log)
// happens in BoardRenderer/InfoRenderer through Img - this class just owns the
// window, the mouse input, and the timer that drives the passage of time.
//
// Everything is rendered at the monitor's real pixel density (not just the
// window's logical size) and the final blit uses an explicit destination size -
// otherwise on a scaled display (125%, 150%...) the whole picture, baked-in text
// included, gets stretched by the OS and turns blurry.
public class GameWindow {
    private final GameSession session;
    private final BoardRenderer boardRenderer;
    private final InfoRenderer infoRenderer;
    private final JFrame frame;
    private final DisplaySurface displaySurface;
    private final double deviceScale;
    private long lastTickMillis;

    public GameWindow(GameSession session, int rows, int cols,
                       String whiteName, String blackName,
                       ScoreTracker scoreTracker, MovesLog movesLog) {
        this.session = session;
        this.boardRenderer = new BoardRenderer(rows, cols);
        this.infoRenderer = new InfoRenderer(whiteName, blackName, scoreTracker, movesLog);
        this.deviceScale = detectScale();

        frame = new JFrame("Board");
        displaySurface = new DisplaySurface();
        frame.setLayout(new BorderLayout());
        frame.add(displaySurface, BorderLayout.CENTER);
        frame.setSize(1000, 800);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);

        displaySurface.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) { handleClick(e); }
        });

        frame.setVisible(true);

        lastTickMillis = System.currentTimeMillis();
        Timer timer = new Timer(16, e -> tick());
        timer.start();

        renderNow();
    }

    private static double detectScale() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration()
                    .getDefaultTransform().getScaleX();
        } catch (Exception e) {
            return 1.0;
        }
    }

    private void handleClick(MouseEvent e) {
        // Mouse coordinates are logical; the geometry left over from the last
        // render is in device pixels, so scale up before hit-testing against it.
        int sideWidth = (int) Math.round(infoRenderer.getSideWidth() * deviceScale);
        int topHeight = (int) Math.round(infoRenderer.getTopHeight() * deviceScale);

        int boardX = (int) Math.round(e.getX() * deviceScale) - sideWidth;
        int boardY = (int) Math.round(e.getY() * deviceScale) - topHeight;
        int[] cell = boardRenderer.getGeometry().pixelToCell(boardX, boardY);
        if (cell == null) return;

        if (SwingUtilities.isRightMouseButton(e)) {
            session.activateJump(cell[0], cell[1]);
        } else {
            Position selected = session.getSelectedPosition();
            boolean clickedTheAlreadySelectedSquare = selected != null
                    && selected.getRow() == cell[0] && selected.getCol() == cell[1];
            if (clickedTheAlreadySelectedSquare) {
                session.activateJump(cell[0], cell[1]);
                session.deselect();
            } else {
                session.click(cell[1] * GameConfig.CELL_SIZE, cell[0] * GameConfig.CELL_SIZE);
            }
        }
        renderNow();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastTickMillis;
        lastTickMillis = now;
        session.waitMs(elapsed);
        renderNow();
    }

    private void renderNow() {
        int logicalWidth = Math.max(200, displaySurface.getWidth() > 0 ? displaySurface.getWidth() : 1000);
        int logicalHeight = Math.max(200, displaySurface.getHeight() > 0 ? displaySurface.getHeight() : 800);

        int totalWidth = (int) Math.round(logicalWidth * deviceScale);
        int totalHeight = (int) Math.round(logicalHeight * deviceScale);
        int sideWidth = (int) Math.round(infoRenderer.getSideWidth() * deviceScale);
        int topHeight = (int) Math.round(infoRenderer.getTopHeight() * deviceScale);
        int bottomHeight = (int) Math.round(infoRenderer.getBottomHeight() * deviceScale);
        int boardWidth = Math.max(1, totalWidth - sideWidth * 2);
        int boardHeight = Math.max(1, totalHeight - topHeight - bottomHeight);

        Img canvas = Img.blank(totalWidth, totalHeight);
        infoRenderer.renderTopBar(totalWidth).drawOn(canvas, 0, 0);
        infoRenderer.renderBottomBar(totalWidth).drawOn(canvas, 0, topHeight + boardHeight);
        infoRenderer.renderLeftPanel(boardHeight).drawOn(canvas, 0, topHeight);
        infoRenderer.renderRightPanel(boardHeight).drawOn(canvas, sideWidth + boardWidth, topHeight);
        boardRenderer.render(session.snapshot(), boardWidth, boardHeight).drawOn(canvas, sideWidth, topHeight);

        displaySurface.setImage(canvas.get());
    }

    // A plain pixel surface: it only ever blits the one finished Img it's given,
    // scaled from real device pixels down to the component's logical size - the
    // same "put this image on screen" step Img.show() itself does, just kept
    // crisp on scaled displays and reusable frame over frame instead of opening
    // a brand new window every time.
    private static final class DisplaySurface extends JComponent {
        private BufferedImage image;

        void setImage(BufferedImage image) {
            this.image = image;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image == null) return;
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(image, 0, 0, getWidth(), getHeight(), null);
        }
    }
}
