import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

// Client entry point for the networked game. Login (username+password) and
// starting a search for an opponent both happen as shell prompts, per the
// course's Home-screen requirement; once matched, a NetworkGameWindow opens
// and everything from there on is mouse clicks, exactly like local play.
//
// A Home window (Play / Room buttons, each with a one-line explanation, plus
// a status line that always says what's currently happening) runs alongside
// the console prompt: Play mirrors the console's ELO search, Room opens the
// Create/Join/Cancel dialog for private-room play. Nothing playable (no board,
// no clicking pieces) exists until the server confirms two real participants -
// the game window itself is never created before that.
public class NetworkClientDemo implements GameClientListener {
    private final Scanner console;
    private final ActivityLog log;
    private volatile NetworkGameWindow window;
    private volatile GameClient currentClient;
    private volatile Bus matchBus;
    private volatile JFrame homeWindow;
    private volatile JLabel statusLabel;
    private volatile JButton playButton;
    private volatile JButton roomButton;
    private final Object matchLock = new Object();
    private volatile boolean matchDecided = false;

    public NetworkClientDemo() throws IOException {
        // Windows' console defaults to a legacy codepage, not UTF-8 - without this,
        // typing a non-ASCII username (e.g. Hebrew) reads back as mojibake, and
        // printing one right does too. Both directions need to agree on UTF-8.
        System.setOut(new PrintStream(System.out, true, "UTF-8"));
        System.setErr(new PrintStream(System.err, true, "UTF-8"));
        console = new Scanner(System.in, StandardCharsets.UTF_8);
        log = new ActivityLog("client-" + ProcessHandle.current().pid() + ".log");
    }

    public static void main(String[] args) throws Exception {
        new NetworkClientDemo().run();
    }

    private void run() throws Exception {
        System.out.print("Server host [localhost]: ");
        String host = console.nextLine().trim();
        if (host.isEmpty()) host = "localhost";

        System.out.print("Server port [5000]: ");
        String portInput = console.nextLine().trim();
        int port = portInput.isEmpty() ? 5000 : Integer.parseInt(portInput);

        GameClient client = null;
        String username = null;
        while (client == null) {
            System.out.print("Username: ");
            username = console.nextLine().trim();
            System.out.print("Password: ");
            String password = console.nextLine().trim();
            try {
                client = new GameClient(host, port, username, password, log, this);
                currentClient = client;
                System.out.println("Welcome, " + username + " (elo " + client.getElo() + ").");
            } catch (Exception e) {
                System.out.println("Login failed: " + e.getMessage() + " - try again.");
                log.log("Login attempt failed for " + username + ": " + e.getMessage());
            }
        }

        GameClient finalClient = client;
        String finalUsername = username;
        SwingUtilities.invokeLater(() -> showHomeWindow(finalClient, finalUsername));

        System.out.println();
        System.out.println("A window titled \"KamaTech Chess\" just opened - everything from here");
        System.out.println("happens there (Play or Room). This console is only used for login.");

        // Keep the process alive for as long as the window is open.
        Thread.currentThread().join();
    }

    private void showHomeWindow(GameClient client, String username) {
        JFrame home = new JFrame("KamaTech Chess - Home");
        home.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JLabel who = new JLabel("Logged in as " + username + "  (ELO " + client.getElo() + ")", SwingConstants.CENTER);
        who.setFont(who.getFont().deriveFont(Font.BOLD, 14f));
        who.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel optionsPanel = new JPanel(new GridLayout(2, 1, 0, 12));
        optionsPanel.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 20));
        optionsPanel.add(buildOption("Play (Random Match)",
                "Find an opponent near your skill level automatically (up to 1 minute).",
                e -> {
                    setBusy(true);
                    client.sendPlay();
                }));
        optionsPanel.add(buildOption("Room (Play with a Friend)",
                "Create a private room and share the code, or join a friend's room.",
                e -> showRoomDialog(home, client)));

        JLabel status = new JLabel("Nothing happens until a second real player is connected. "
                + "Click Play or Room to start.", SwingConstants.CENTER);
        status.setForeground(new Color(60, 90, 60));
        status.setBorder(BorderFactory.createEmptyBorder(14, 10, 14, 10));
        status.setFont(status.getFont().deriveFont(Font.PLAIN, 13f));

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(who, BorderLayout.NORTH);
        content.add(optionsPanel, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        home.add(content);

        home.setMinimumSize(new java.awt.Dimension(420, 260));
        home.pack();
        home.setLocationRelativeTo(null);
        home.setVisible(true);
        home.toFront();

        homeWindow = home;
        statusLabel = status;
    }

    private JPanel buildOption(String buttonText, String description, java.awt.event.ActionListener onClick) {
        JButton button = new JButton(buttonText);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 13f));
        button.addActionListener(onClick);
        if (buttonText.startsWith("Play")) playButton = button;
        if (buttonText.startsWith("Room")) roomButton = button;

        JLabel desc = new JLabel("<html><div style='width:280px'>" + description + "</div></html>");
        desc.setFont(desc.getFont().deriveFont(Font.PLAIN, 11f));
        desc.setForeground(Color.DARK_GRAY);

        JPanel row = new JPanel(new BorderLayout(10, 2));
        row.add(button, BorderLayout.WEST);
        row.add(desc, BorderLayout.CENTER);
        return row;
    }

    // Disables both buttons while a search/room wait is in progress, so there is
    // never ambiguity about what clicking would even do right now.
    private void setBusy(boolean busy) {
        SwingUtilities.invokeLater(() -> {
            if (playButton != null) playButton.setEnabled(!busy);
            if (roomButton != null) roomButton.setEnabled(!busy);
        });
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> {
            if (statusLabel != null) statusLabel.setText("<html><div style='width:340px;text-align:center'>" + text + "</div></html>");
        });
    }

    // Matches the course's Room dialog: a "room name" text box and Create / Join /
    // Cancel buttons. Create ignores whatever is typed and asks the server to mint
    // a fresh room code; Join sends whatever code was typed.
    private void showRoomDialog(JFrame owner, GameClient client) {
        JDialog dialog = new JDialog(owner, "Room", true);
        dialog.setLayout(new BorderLayout(8, 8));

        JLabel intro = new JLabel("<html><div style='width:260px'>Create a new room to invite a "
                + "friend, or type a friend's room code below and Join.</div></html>");
        intro.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));

        JLabel label = new JLabel("room name");
        JTextField field = new JTextField(12);
        JPanel fieldPanel = new JPanel(new BorderLayout(4, 4));
        fieldPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        fieldPanel.add(label, BorderLayout.NORTH);
        fieldPanel.add(field, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new java.awt.FlowLayout());
        JButton create = new JButton("Create");
        JButton join = new JButton("Join");
        JButton cancel = new JButton("Cancel");
        buttons.add(create);
        buttons.add(join);
        buttons.add(cancel);

        dialog.add(intro, BorderLayout.NORTH);
        dialog.add(fieldPanel, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);

        create.addActionListener(e -> {
            setBusy(true);
            client.sendCreateRoom();
            dialog.dispose();
        });
        join.addActionListener(e -> {
            String roomId = field.getText().trim();
            if (!roomId.isEmpty()) {
                setBusy(true);
                client.sendJoinRoom(roomId);
            }
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());

        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    @Override
    public void onSearching() {
        System.out.println("Searching for an opponent within 100 ELO... (up to 1 minute)");
        log.log("Searching for an opponent (ELO range +-100, timeout 60s)");
        setStatus("Searching for an opponent near your ELO... (up to 1 minute)");
    }

    @Override
    public void onNoMatch() {
        System.out.println("No opponent found in time.");
        log.log("No opponent found in time.");
        setStatus("No opponent found in time. Click Play to try again, or use Room instead.");
        setBusy(false);
        synchronized (matchLock) {
            matchDecided = true;
            matchLock.notifyAll();
        }
    }

    @Override
    public void onMatchFound(String color, String opponentUsername, int opponentElo) {
        System.out.println("Match found! You are " + color + ", opponent: " + opponentUsername + " (elo " + opponentElo + ")");
        log.log("Match found: you=" + color + " opponent=" + opponentUsername + " (elo " + opponentElo + ")");
        String whiteName = "WHITE".equals(color) ? "You" : opponentUsername;
        String blackName = "BLACK".equals(color) ? "You" : opponentUsername;

        try {
            SwingUtilities.invokeAndWait(() -> {
                // Same wiring BoardDemo uses for local play: ScoreTracker/MovesLog/SoundEffects
                // are plain Bus subscribers that don't care where events come from. Here they
                // come from the server's MOVE/GAMEOVER/GAMESTARTED broadcasts, decoded and
                // re-published on this client-local bus (see onMoveResolved/onGameOver/onGameStarted).
                Bus bus = new Bus();
                ScoreTracker scoreTracker = new ScoreTracker();
                MovesLog movesLog = new MovesLog(8);
                scoreTracker.subscribe(bus);
                movesLog.subscribe(bus);
                new SoundEffects().subscribe(bus);
                matchBus = bus;

                window = new NetworkGameWindow(currentClient, 8, 8, whiteName, blackName, scoreTracker, movesLog);
                window.onNames(whiteName, blackName);
                JOptionPane.showMessageDialog(null,
                        "Match found! You are " + color + ".\nOpponent: " + opponentUsername + " (ELO " + opponentElo + ")",
                        "Match Found", JOptionPane.INFORMATION_MESSAGE);
                if (homeWindow != null) homeWindow.dispose();
            });
        } catch (Exception e) {
            e.printStackTrace();
        }

        synchronized (matchLock) {
            matchDecided = true;
            matchLock.notifyAll();
        }
    }

    @Override
    public void onRoomCreated(String roomId) {
        System.out.println("Room created! ID: " + roomId + " - share this with the other player, waiting for them to join...");
        log.log("Room created: " + roomId);
        setStatus("<b>Room code: " + roomId + "</b><br>Give this code to your friend. Waiting for them to join...");
        SwingUtilities.invokeLater(() -> {
            if (homeWindow != null) homeWindow.setTitle("KamaTech Chess - Room " + roomId + " (waiting for opponent)");
        });
    }

    @Override
    public void onRoomNotFound(String roomId) {
        System.out.println("Room not found: " + roomId);
        log.log("Room not found: " + roomId);
        setStatus("Room not found: " + roomId + ". Check the code and try again.");
        setBusy(false);
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(homeWindow, "No room with that code exists: " + roomId,
                        "Room not found", JOptionPane.ERROR_MESSAGE));
    }

    @Override
    public void onSpectateJoined(String roomId, String whiteUsername, String blackUsername) {
        System.out.println("Spectating room " + roomId + ": " + whiteUsername + " vs " + blackUsername);
        log.log("Spectating room " + roomId + ": " + whiteUsername + " vs " + blackUsername);

        try {
            SwingUtilities.invokeAndWait(() -> {
                Bus bus = new Bus();
                ScoreTracker scoreTracker = new ScoreTracker();
                MovesLog movesLog = new MovesLog(8);
                scoreTracker.subscribe(bus);
                movesLog.subscribe(bus);
                new SoundEffects().subscribe(bus);
                matchBus = bus;

                window = new NetworkGameWindow(currentClient, 8, 8, whiteUsername, blackUsername, scoreTracker, movesLog, true);
                window.onNames(whiteUsername, blackUsername);
                window.setTitle("KamaTech Chess - Spectating room " + roomId + ": " + whiteUsername + " vs " + blackUsername);
                JOptionPane.showMessageDialog(null,
                        "You are spectating room " + roomId + ".\n" + whiteUsername + " (White) vs " + blackUsername + " (Black)"
                                + "\n\nYou can watch the board but cannot move any pieces.",
                        "Spectating", JOptionPane.INFORMATION_MESSAGE);
                if (homeWindow != null) homeWindow.dispose();
            });
        } catch (Exception e) {
            e.printStackTrace();
        }

        synchronized (matchLock) {
            matchDecided = true;
            matchLock.notifyAll();
        }
    }

    @Override
    public void onSnapshot(GameSnapshot snapshot) {
        if (window != null) window.onSnapshot(snapshot);
    }

    @Override
    public void onOpponentDisconnected(int secondsRemaining) {
        if (secondsRemaining == 0) log.log("Opponent's disconnect grace period expired.");
        if (window != null) window.onOpponentDisconnected(secondsRemaining);
    }

    @Override
    public void onMoveResolved(MoveEvent event) {
        log.log("Move: " + event.piece.getColor() + " " + event.piece.getType()
                + " " + square(event.from) + "->" + square(event.to)
                + (event.wasCapture ? " captures " + event.capturedPiece.getColor() + " " + event.capturedPiece.getType() : ""));
        Bus bus = matchBus;
        if (bus != null) bus.publish(GameSession.TOPIC_MOVE_RESOLVED, event);
    }

    @Override
    public void onGameOver(GameOverEvent event) {
        log.log("Game over, winner=" + event.winnerColor);
        Bus bus = matchBus;
        if (bus != null) bus.publish(GameSession.TOPIC_GAME_OVER, event);
    }

    @Override
    public void onGameStarted(GameStartedEvent event) {
        log.log("Game started.");
        Bus bus = matchBus;
        if (bus != null) bus.publish(GameSession.TOPIC_GAME_STARTED, event);
    }

    private static String square(Position pos) {
        return "" + (char) ('a' + pos.getCol()) + pos.getRow();
    }
}
