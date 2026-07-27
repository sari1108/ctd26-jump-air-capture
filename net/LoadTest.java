import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// Observability's "load tests" (course diagram component #6) - not a theoretical
// estimate, an empirical one. Spins up N simulated players (real GameClient
// connections: real LOGIN, real PLAY) against a real running ServerMain, and reports
// how many actually connected, logged in, and got matched. Run it yourself:
//   java -cp out LoadTest localhost 5000 200
// See Server_Design.md for real numbers this already found - including a genuine bug
// (ServerSocket's default accept backlog dropping connections under a burst) that this
// tool caught and that's now fixed.
public class LoadTest {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        int n = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        if (n % 2 != 0) n++; // matchmaking pairs players - keep it even

        ActivityLog log = new ActivityLog("loadtest.log");
        AtomicInteger connected = new AtomicInteger();
        AtomicInteger loggedIn = new AtomicInteger();
        AtomicInteger connectFailed = new AtomicInteger();
        AtomicInteger matched = new AtomicInteger();
        AtomicInteger noMatch = new AtomicInteger();
        AtomicLong firstMatchMs = new AtomicLong(-1);
        AtomicLong lastMatchMs = new AtomicLong(-1);
        CountDownLatch allMatched = new CountDownLatch(n);
        long start = System.currentTimeMillis();

        Thread[] workers = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            workers[i] = new Thread(() -> {
                try {
                    GameClient client = new GameClient(host, port, "load_" + idx, "pw", log, new GameClientListener() {
                        public void onMatchFound(String color, String opp, int elo) {
                            long now = System.currentTimeMillis() - start;
                            firstMatchMs.compareAndSet(-1, now);
                            lastMatchMs.set(now);
                            matched.incrementAndGet();
                            allMatched.countDown();
                        }
                        public void onNoMatch() {
                            noMatch.incrementAndGet();
                            allMatched.countDown();
                        }
                    });
                    connected.incrementAndGet();
                    loggedIn.incrementAndGet();
                    client.sendPlay();
                } catch (Exception e) {
                    connectFailed.incrementAndGet();
                    allMatched.countDown();
                }
            });
            workers[i].start();
            if (idx % 50 == 0) Thread.sleep(20); // avoid an instant thundering-herd connect burst
        }

        boolean completed = allMatched.await(90, java.util.concurrent.TimeUnit.SECONDS);
        long totalMs = System.currentTimeMillis() - start;

        System.out.println("=== LOAD TEST RESULTS (n=" + n + ") ===");
        System.out.println("connected:            " + connected.get() + "/" + n);
        System.out.println("logged in:            " + loggedIn.get() + "/" + n);
        System.out.println("connect failures:     " + connectFailed.get());
        System.out.println("matched:              " + matched.get());
        System.out.println("no_match/timeout:     " + noMatch.get());
        System.out.println("completed within 90s: " + completed);
        System.out.println("time to first match:  " + firstMatchMs.get() + "ms");
        System.out.println("time to last match:   " + lastMatchMs.get() + "ms");
        System.out.println("total wall time:      " + totalMs + "ms");
        System.exit(0);
    }
}
