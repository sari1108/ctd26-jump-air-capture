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

// Network twin of GameWindow: instead of owning a local GameSession, it holds
// whatever GameSnapshot the server last broadcast over the GameClient, and
// forwards clicks to the server instead of applying them locally. Rendering
// (BoardRenderer/InfoRenderer) and the HiDPI-aware display surface are
// identical to the local game - only where the snapshot comes from, and
// where clicks go, differ.
public class NetworkGameWindow {
    private final GameClient gameClient;
    private final BoardRenderer boardRenderer;
    private final InfoRenderer infoRenderer;
    private final JFrame frame;
    private final DisplaySurface displaySurface;
    private final double deviceScale;
    private volatile GameSnapshot latestSnapshot;

    public NetworkGameWindow(GameClient gameClient, int rows, int cols,
                              String whiteName, String blackName,
                              ScoreTracker scoreTracker, MovesLog movesLog) {
        this.gameClient = gameClient;
        this.boardRenderer = new BoardRenderer(rows, cols);
        this.infoRenderer = new InfoRenderer(whiteName, blackName, scoreTracker, movesLog);
        this.deviceScale = detectScale();

        frame = new JFrame("Board (network)");
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
    }

    // Called from GameClient's reader thread whenever the server broadcasts
    // a new snapshot - schedule the repaint on the Swing thread.
    public void onSnapshot(GameSnapshot snapshot) {
        latestSnapshot = snapshot;
        SwingUtilities.invokeLater(this::renderNow);
    }

    public void onNames(String whiteName, String blackName) {
        SwingUtilities.invokeLater(() -> {
            infoRenderer.setNames(whiteName, blackName);
            renderNow();
        });
    }

    public void onOpponentDisconnected(int secondsRemaining) {
        SwingUtilities.invokeLater(() -> frame.setTitle(secondsRemaining > 0
                ? "Board (network) - opponent disconnected, auto-win in " + secondsRemaining + "s"
                : "Board (network)"));
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
        int sideWidth = (int) Math.round(infoRenderer.getSideWidth() * deviceScale);
        int topHeight = (int) Math.round(infoRenderer.getTopHeight() * deviceScale);

        int boardX = (int) Math.round(e.getX() * deviceScale) - sideWidth;
        int boardY = (int) Math.round(e.getY() * deviceScale) - topHeight;
        int[] cell = boardRenderer.getGeometry().pixelToCell(boardX, boardY);
        if (cell == null) return;

        if (SwingUtilities.isRightMouseButton(e)) {
            gameClient.sendJump(cell[0], cell[1]);
            return;
        }

        GameSnapshot snapshot = latestSnapshot;
        Position selected = snapshot == null ? null : snapshot.getPositionSelected();
        boolean clickedTheAlreadySelectedSquare = selected != null
                && selected.getRow() == cell[0] && selected.getCol() == cell[1];
        if (clickedTheAlreadySelectedSquare) {
            gameClient.sendJump(cell[0], cell[1]);
            gameClient.sendDeselect();
        } else {
            gameClient.sendClick(cell[0], cell[1]);
        }
    }

    private void renderNow() {
        GameSnapshot snapshot = latestSnapshot;
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
