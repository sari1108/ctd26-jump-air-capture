import java.awt.Color;
import java.util.List;

// Builds the name/score bars and the per-color moves log as Img objects - text and
// panel backgrounds only, composited with putText/fillRect, no JTable/JLabel.
public class InfoRenderer {
    private static final int SIDE_WIDTH = 200;
    private static final int TOP_HEIGHT = 40;
    private static final int BOTTOM_HEIGHT = 40;
    private static final int LINE_HEIGHT = 22;
    private static final Color PANEL_BG = new Color(238, 238, 238);
    private static final Color BAR_BG = new Color(222, 222, 222);

    private String whiteName;
    private String blackName;
    private final ScoreTracker scoreTracker;
    private final MovesLog movesLog;

    public InfoRenderer(String whiteName, String blackName, ScoreTracker scoreTracker, MovesLog movesLog) {
        this.whiteName = whiteName;
        this.blackName = blackName;
        this.scoreTracker = scoreTracker;
        this.movesLog = movesLog;
    }

    // Network play only learns real player names after login/handshake -
    // lets the display update once they're known instead of staying "White"/"Black".
    public void setNames(String whiteName, String blackName) {
        this.whiteName = whiteName;
        this.blackName = blackName;
    }

    public int getSideWidth() { return SIDE_WIDTH; }
    public int getTopHeight() { return TOP_HEIGHT; }
    public int getBottomHeight() { return BOTTOM_HEIGHT; }

    public Img renderTopBar(int width) {
        Img bar = Img.blank(width, TOP_HEIGHT);
        bar.fillRect(0, 0, width, TOP_HEIGHT, BAR_BG);
        String text = "Name: " + whiteName + "     Score: " + scoreTracker.getScore(PieceColor.WHITE);
        bar.putText(text, 12, TOP_HEIGHT - 14, 1.15f, Color.BLACK, 1);
        return bar;
    }

    public Img renderBottomBar(int width) {
        Img bar = Img.blank(width, BOTTOM_HEIGHT);
        bar.fillRect(0, 0, width, BOTTOM_HEIGHT, BAR_BG);
        String text = "Name: " + blackName + "     Score: " + scoreTracker.getScore(PieceColor.BLACK);
        bar.putText(text, 12, BOTTOM_HEIGHT - 14, 1.15f, Color.BLACK, 1);
        return bar;
    }

    public Img renderLeftPanel(int height) {
        return renderSide("Black", movesLog.entriesFor(PieceColor.BLACK), height);
    }

    public Img renderRightPanel(int height) {
        return renderSide("White", movesLog.entriesFor(PieceColor.WHITE), height);
    }

    private Img renderSide(String title, List<MovesLog.Entry> entries, int height) {
        Img panel = Img.blank(SIDE_WIDTH, height);
        panel.fillRect(0, 0, SIDE_WIDTH, height, PANEL_BG);
        panel.putText(title, SIDE_WIDTH / 2 - title.length() * 5, 22, 1.2f, Color.BLACK, 1);
        panel.putText("Time        Move", 8, 42, 0.8f, new Color(110, 110, 110), 0);

        int maxLines = Math.max(0, (height - 55) / LINE_HEIGHT);
        int from = Math.max(0, entries.size() - maxLines);
        int y = 60;
        for (int i = from; i < entries.size(); i++) {
            MovesLog.Entry e = entries.get(i);
            panel.putText(formatTime(e.time) + "   " + e.notation, 8, y, 0.85f, new Color(40, 40, 40), 0);
            y += LINE_HEIGHT;
        }
        return panel;
    }

    private String formatTime(long millis) {
        long minutes = millis / 60000;
        long seconds = (millis / 1000) % 60;
        long ms = millis % 1000;
        return String.format("%02d:%02d.%03d", minutes, seconds, ms);
    }
}
