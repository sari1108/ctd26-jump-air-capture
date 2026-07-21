import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// Minimal persistent activity logger: appends timestamped lines to a file and
// echoes them to the console. No external logging framework - lib/slf4j-nop.jar
// only exists to silence sqlite-jdbc's internal slf4j calls, it's a no-op
// binding and isn't a backend anything else can log through.
public final class ActivityLog implements AutoCloseable {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final PrintWriter writer;

    // Explicit UTF-8, not FileWriter's platform-default charset - otherwise a
    // non-ASCII username (e.g. Hebrew) logs as mojibake regardless of what the
    // console itself is doing.
    public ActivityLog(String filePath) throws IOException {
        writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(filePath, true), StandardCharsets.UTF_8), true);
    }

    public synchronized void log(String message) {
        String line = "[" + LocalDateTime.now().format(TIMESTAMP) + "] " + message;
        System.out.println(line);
        writer.println(line);
    }

    @Override
    public synchronized void close() {
        writer.close();
    }
}
