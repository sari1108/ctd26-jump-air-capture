import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

// The only Swing this game uses beyond a plain pixel surface: one JFrame holding
// one lightweight component that displays a single composited Img, rebuilt every
// tick. All actual drawing (board, pieces, highlights, names/score/moves log)
// happens in BoardRenderer/InfoRenderer through Img - this class just owns the
// mouse input and the timer that drives the passage of time; the window chrome
// and layout math it shares with NetworkGameWindow live in BoardWindow.
public class GameWindow extends BoardWindow {
    private final GameSession session;
    private long lastTickMillis;

    public GameWindow(GameSession session, int rows, int cols,
                       String whiteName, String blackName,
                       ScoreTracker scoreTracker, MovesLog movesLog) {
        super("Board", rows, cols, whiteName, blackName, scoreTracker, movesLog);
        this.session = session;

        show();

        lastTickMillis = System.currentTimeMillis();
        Timer timer = new Timer(16, e -> tick());
        timer.start();

        renderFrame(session.snapshot());
    }

    @Override
    protected void handleClick(MouseEvent e) {
        int[] cell = cellAt(e);
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
                session.selectOrMove(cell[0], cell[1]);
            }
        }
        renderFrame(session.snapshot());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastTickMillis;
        lastTickMillis = now;
        session.waitMs(elapsed);
        renderFrame(session.snapshot());
    }
}
