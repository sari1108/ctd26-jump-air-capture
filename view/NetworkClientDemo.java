import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.Scanner;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

// Client entry point for the networked game. Login (username+password) and
// starting a search for an opponent both happen as shell prompts, per the
// course's Home-screen requirement; once matched, a NetworkGameWindow opens
// and everything from there on is mouse clicks, exactly like local play.
//
// A small Home window (Play / Room buttons) runs alongside the console prompt:
// Play mirrors the console's ELO search, Room opens the Create/Join/Cancel
// dialog for private-room play (the second joiner becomes Black, anyone after
// that is a read-only spectator).
public class NetworkClientDemo implements GameClientListener {
    private final Scanner console = new Scanner(System.in);
    private final ActivityLog log;
    private volatile NetworkGameWindow window;
    private volatile GameClient currentClient;
    private volatile Bus matchBus;
    private volatile JFrame homeWindow;
    private volatile JLabel homeStatusLabel;
    private final Object matchLock = new Object();
    private volatile boolean matchDecided = false;

    public NetworkClientDemo() throws IOException {
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
        while (client == null) {
            System.out.print("Username: ");
            String username = console.nextLine().trim();
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
        SwingUtilities.invokeLater(() -> showHomeWindow(finalClient));

        while (window == null) {
            System.out.print("Press Enter to search for an opponent (Play)... ");
            console.nextLine();
            if (window != null) break;
            matchDecided = false;
            client.sendPlay();
            synchronized (matchLock) {
                while (!matchDecided) matchLock.wait();
            }
        }

        // Keep the process alive for as long as the window is open.
        Thread.currentThread().join();
    }

    private void showHomeWindow(GameClient client) {
        JFrame home = new JFrame("KamaTech Chess");
        home.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JButton playButton = new JButton("Play (ELO match)");
        JButton roomButton = new JButton("Room");
        JLabel status = new JLabel(" ");

        JPanel buttons = new JPanel(new FlowLayout());
        buttons.add(playButton);
        buttons.add(roomButton);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.add(buttons, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        home.add(content);

        playButton.addActionListener(e -> {
            matchDecided = false;
            client.sendPlay();
        });
        roomButton.addActionListener(e -> showRoomDialog(home, client));

        home.pack();
        home.setLocationRelativeTo(null);
        home.setVisible(true);

        homeWindow = home;
        homeStatusLabel = status;
    }

    // Matches the course's Room dialog exactly: a "room name" text box and
    // Create / Join / Cancel buttons. Create ignores whatever is typed and asks
    // the server to mint a fresh room code; Join sends whatever code was typed.
    private void showRoomDialog(JFrame owner, GameClient client) {
        JDialog dialog = new JDialog(owner, "Room", true);
        dialog.setLayout(new BorderLayout(6, 6));

        JLabel label = new JLabel("room name");
        JTextField field = new JTextField(12);
        JPanel buttons = new JPanel(new FlowLayout());
        JButton create = new JButton("Create");
        JButton join = new JButton("Join");
        JButton cancel = new JButton("Cancel");
        buttons.add(create);
        buttons.add(join);
        buttons.add(cancel);

        dialog.add(label, BorderLayout.NORTH);
        dialog.add(field, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);

        create.addActionListener(e -> {
            client.sendCreateRoom();
            dialog.dispose();
        });
        join.addActionListener(e -> {
            String roomId = field.getText().trim();
            if (!roomId.isEmpty()) {
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
    }

    @Override
    public void onNoMatch() {
        System.out.println("No opponent found in time.");
        log.log("No opponent found in time.");
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
        SwingUtilities.invokeLater(() -> {
            if (homeWindow != null) homeWindow.setTitle("Room " + roomId + " - waiting for opponent...");
            if (homeStatusLabel != null) homeStatusLabel.setText("Room ID: " + roomId + " (waiting for opponent)");
        });
    }

    @Override
    public void onRoomNotFound(String roomId) {
        System.out.println("Room not found: " + roomId);
        log.log("Room not found: " + roomId);
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(homeWindow, "Room not found: " + roomId, "Room", JOptionPane.ERROR_MESSAGE));
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
                window.setTitle("Room " + roomId + " - spectating: " + whiteUsername + " vs " + blackUsername);
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
