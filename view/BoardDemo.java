import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public class BoardDemo {
    private static final String STARTING_POSITION =
            "bR bN bB bQ bK bB bN bR\n" +
            "bP bP bP bP bP bP bP bP\n" +
            ".  .  .  .  .  .  .  .\n" +
            ".  .  .  .  .  .  .  .\n" +
            ".  .  .  .  .  .  .  .\n" +
            ".  .  .  .  .  .  .  .\n" +
            "wP wP wP wP wP wP wP wP\n" +
            "wR wN wB wQ wK wB wN wR\n";

    public static void main(String[] args) {
        Board board = new BoardParser().parse(STARTING_POSITION);
        GameSession session = new GameSession(board);

        String whiteName = JOptionPane.showInputDialog(null, "White player name:", "New Game", JOptionPane.PLAIN_MESSAGE);
        if (whiteName == null || whiteName.trim().isEmpty()) whiteName = "White";
        String blackName = JOptionPane.showInputDialog(null, "Black player name:", "New Game", JOptionPane.PLAIN_MESSAGE);
        if (blackName == null || blackName.trim().isEmpty()) blackName = "Black";

        ScoreTracker scoreTracker = new ScoreTracker();
        MovesLog movesLog = new MovesLog(board.getRows());
        SoundEffects soundEffects = new SoundEffects();
        scoreTracker.subscribe(session.getBus());
        movesLog.subscribe(session.getBus());
        soundEffects.subscribe(session.getBus());

        String finalWhiteName = whiteName;
        String finalBlackName = blackName;
        SwingUtilities.invokeLater(() -> {
            new GameWindow(session, board.getRows(), board.getCols(), finalWhiteName, finalBlackName, scoreTracker, movesLog);
            session.start();
        });
    }
}
