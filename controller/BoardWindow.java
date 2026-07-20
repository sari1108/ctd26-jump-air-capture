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

// Shared chrome for the two Swing windows this game opens (local play and
// network play): the HiDPI-aware pixel surface, the board/info layout math,
// and click-to-cell hit testing. Subclasses differ only in where the
// GameSnapshot they render comes from and where clicks get routed.
public abstract class BoardWindow {
    protected final BoardRenderer boardRenderer;
    protected final InfoRenderer infoRenderer;
    protected final JFrame frame;
    private final DisplaySurface displaySurface;
    protected final double deviceScale;

    protected BoardWindow(String title, int rows, int cols, String whiteName, String blackName,
                           ScoreTracker scoreTracker, MovesLog movesLog) {
        this.boardRenderer = new BoardRenderer(rows, cols);
        this.infoRenderer = new InfoRenderer(whiteName, blackName, scoreTracker, movesLog);
        this.deviceScale = detectScale();

        frame = new JFrame(title);
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

    // Mouse coordinates are logical; the geometry left over from the last
    // render is in device pixels, so scale up before hit-testing against it.
    // Returns null if the click landed outside the board.
    protected int[] cellAt(MouseEvent e) {
        int sideWidth = (int) Math.round(infoRenderer.getSideWidth() * deviceScale);
        int topHeight = (int) Math.round(infoRenderer.getTopHeight() * deviceScale);

        int boardX = (int) Math.round(e.getX() * deviceScale) - sideWidth;
        int boardY = (int) Math.round(e.getY() * deviceScale) - topHeight;
        return boardRenderer.getGeometry().pixelToCell(boardX, boardY);
    }

    protected abstract void handleClick(MouseEvent e);

    protected void renderFrame(GameSnapshot snapshot) {
        if (snapshot == null) return;

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
        boardRenderer.render(snapshot, boardWidth, boardHeight).drawOn(canvas, sideWidth, topHeight);

        displaySurface.setImage(canvas.get());
    }

    protected void show() { frame.setVisible(true); }

    protected void setTitle(String title) { frame.setTitle(title); }

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
