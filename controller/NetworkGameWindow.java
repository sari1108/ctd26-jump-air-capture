import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;

// Network twin of GameWindow: instead of owning a local GameSession, it holds
// whatever GameSnapshot the server last broadcast over the GameClient, and
// forwards clicks to the server instead of applying them locally. Rendering
// and the HiDPI-aware display surface are inherited from BoardWindow, shared
// with the local game - only where the snapshot comes from, and where clicks
// go, differ.
public class NetworkGameWindow extends BoardWindow {
    private final GameClient gameClient;
    private final boolean spectator;
    private volatile GameSnapshot latestSnapshot;

    public NetworkGameWindow(GameClient gameClient, int rows, int cols,
                              String whiteName, String blackName,
                              ScoreTracker scoreTracker, MovesLog movesLog) {
        this(gameClient, rows, cols, whiteName, blackName, scoreTracker, movesLog, false);
    }

    // spectator=true opens the same board/rendering with no interaction: a room's
    // third-and-later joiners watch the match already in progress but own no seat.
    public NetworkGameWindow(GameClient gameClient, int rows, int cols,
                              String whiteName, String blackName,
                              ScoreTracker scoreTracker, MovesLog movesLog, boolean spectator) {
        super(spectator ? "Board (spectating)" : "Board (network)", rows, cols, whiteName, blackName, scoreTracker, movesLog);
        this.gameClient = gameClient;
        this.spectator = spectator;
        show();
    }

    // Called from GameClient's reader thread whenever the server broadcasts
    // a new snapshot - schedule the repaint on the Swing thread.
    public void onSnapshot(GameSnapshot snapshot) {
        latestSnapshot = snapshot;
        SwingUtilities.invokeLater(() -> renderFrame(snapshot));
    }

    public void onNames(String whiteName, String blackName) {
        SwingUtilities.invokeLater(() -> {
            infoRenderer.setNames(whiteName, blackName);
            renderFrame(latestSnapshot);
        });
    }

    public void onOpponentDisconnected(int secondsRemaining) {
        SwingUtilities.invokeLater(() -> setTitle(secondsRemaining > 0
                ? "Board (network) - opponent disconnected, auto-win in " + secondsRemaining + "s"
                : "Board (network)"));
    }

    @Override
    protected void handleClick(MouseEvent e) {
        if (spectator) return; // view-only - nothing a spectator clicks can act on the game

        int[] cell = cellAt(e);
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
}
