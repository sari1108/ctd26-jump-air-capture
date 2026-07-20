import java.util.Scanner;
import javax.swing.SwingUtilities;

// Client entry point for the networked game. Login (username+password) and
// starting a search for an opponent both happen as shell prompts, per the
// course's Home-screen requirement; once matched, a NetworkGameWindow opens
// and everything from there on is mouse clicks, exactly like local play.
public class NetworkClientDemo implements GameClientListener {
    private final Scanner console = new Scanner(System.in);
    private volatile NetworkGameWindow window;
    private volatile GameClient currentClient;
    private final Object matchLock = new Object();
    private volatile boolean matchDecided = false;

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
                client = new GameClient(host, port, username, password, this);
                currentClient = client;
                System.out.println("Welcome, " + username + " (elo " + client.getElo() + ").");
            } catch (Exception e) {
                System.out.println("Login failed: " + e.getMessage() + " - try again.");
            }
        }

        while (window == null) {
            System.out.print("Press Enter to search for an opponent (Play)... ");
            console.nextLine();
            matchDecided = false;
            client.sendPlay();
            synchronized (matchLock) {
                while (!matchDecided) matchLock.wait();
            }
        }

        // Keep the process alive for as long as the window is open.
        Thread.currentThread().join();
    }

    @Override
    public void onSearching() {
        System.out.println("Searching for an opponent within 100 ELO... (up to 1 minute)");
    }

    @Override
    public void onNoMatch() {
        System.out.println("No opponent found in time.");
        synchronized (matchLock) {
            matchDecided = true;
            matchLock.notifyAll();
        }
    }

    @Override
    public void onMatchFound(String color, String opponentUsername, int opponentElo) {
        System.out.println("Match found! You are " + color + ", opponent: " + opponentUsername + " (elo " + opponentElo + ")");
        String whiteName = "WHITE".equals(color) ? "You" : opponentUsername;
        String blackName = "BLACK".equals(color) ? "You" : opponentUsername;

        try {
            SwingUtilities.invokeAndWait(() -> {
                ScoreTracker scoreTracker = new ScoreTracker();
                MovesLog movesLog = new MovesLog(8);
                window = new NetworkGameWindow(currentClient, 8, 8, whiteName, blackName, scoreTracker, movesLog);
                window.onNames(whiteName, blackName);
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
        if (window != null) window.onOpponentDisconnected(secondsRemaining);
    }
}
