import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;

// Server-side user store: username + password + ELO. Runs on SQLite for local/native
// runs (a single embedded file - see Server_Design.md Q1 for why that's fine at this
// scale) or on PostgreSQL when given a "jdbc:postgresql:..." URL, which is what the
// Docker Compose stack uses - same schema, same queries, just a real client-server DB
// behind it so more than one server instance can safely share it.
// Passwords are salted-hashed with repeated SHA-256 rounds - never stored
// or compared in plain text. First login for a new username registers it;
// a known username must match its stored password.
public class UserDatabase implements AutoCloseable {
    private static final int STARTING_ELO = 1200;

    // New accounts get this many rounds of stretching. Existing rows keep
    // whatever round count they were created with (see the "iterations"
    // column migration below), so upgrading this value never invalidates
    // passwords already stored in a live users.db.
    private static final int HASH_ITERATIONS = 120_000;

    private final Connection connection;

    // dbLocation is either a plain file path (e.g. "users.db", SQLite) or a full
    // "jdbc:postgresql://host:port/dbname?user=...&password=..." URL.
    public UserDatabase(String dbLocation) throws SQLException {
        boolean postgres = dbLocation.startsWith("jdbc:postgresql:");
        String driverClass = postgres ? "org.postgresql.Driver" : "org.sqlite.JDBC";
        String expectedJar = postgres ? "lib/postgresql.jar" : "lib/sqlite-jdbc.jar";
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(driverClass + " driver not on classpath (expected " + expectedJar + ")", e);
        }
        connection = DriverManager.getConnection(postgres ? dbLocation : "jdbc:sqlite:" + dbLocation);
        try (PreparedStatement create = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS users (" +
                        "username TEXT PRIMARY KEY," +
                        "password_hash TEXT NOT NULL," +
                        "salt TEXT NOT NULL," +
                        "iterations INTEGER NOT NULL DEFAULT 1," +
                        "elo INTEGER NOT NULL DEFAULT " + STARTING_ELO + ")")) {
            create.execute();
        }
        // Migration for a users.db created before the "iterations" column existed:
        // CREATE TABLE IF NOT EXISTS is a no-op on an existing table, so a pre-existing
        // file needs the column added explicitly. Its rows default to 1 (their original
        // single-pass hash), so accounts created under the old scheme keep logging in.
        try (PreparedStatement migrate = connection.prepareStatement(
                "ALTER TABLE users ADD COLUMN iterations INTEGER NOT NULL DEFAULT 1")) {
            migrate.execute();
        } catch (SQLException alreadyExists) {
            // column already present (fresh DB, or migration already ran) - nothing to do
        }

        // "games" answers the course diagram's "PostgreSQL stores ... games, results" -
        // one row per finished match (Match.onGameOver), not per move: a match produces
        // exactly one game-over event, so this is the same low-frequency write pattern
        // as the ELO update right next to it. No primary key by design (an insert-only
        // log, nothing ever updates a row) - keeps the schema identical, portable SQL
        // across SQLite and Postgres with no DB-specific autoincrement syntax needed.
        try (PreparedStatement create = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS games (" +
                        "white_username TEXT NOT NULL," +
                        "black_username TEXT NOT NULL," +
                        "winner_color TEXT NOT NULL," +
                        "ended_at BIGINT NOT NULL)")) {
            create.execute();
        }
    }

    // Full per-move history (every move, not just the final result) is deliberately not
    // persisted here - that would mean a DB write on every single move of a live match,
    // touching the hot gameplay path this close to the deadline. Recorded once, at
    // game-over, same as the ELO update beside it in Match.onGameOver.
    public synchronized void recordGame(String whiteUsername, String blackUsername, String winnerColor, long endedAt) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO games (white_username, black_username, winner_color, ended_at) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, whiteUsername);
            insert.setString(2, blackUsername);
            insert.setString(3, winnerColor);
            insert.setLong(4, endedAt);
            insert.executeUpdate();
        }
    }

    // Backs the REST /history endpoint (HealthServer) - the diagram's "REST/HTTP for
    // ... history" requirement. Most recent first, capped so one very active player
    // can't turn this into an unbounded query.
    public synchronized java.util.List<GameRecord> listGames(String username, int limit) throws SQLException {
        java.util.List<GameRecord> results = new java.util.ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT white_username, black_username, winner_color, ended_at FROM games " +
                        "WHERE white_username = ? OR black_username = ? " +
                        "ORDER BY ended_at DESC LIMIT ?")) {
            select.setString(1, username);
            select.setString(2, username);
            select.setInt(3, limit);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    results.add(new GameRecord(
                            rs.getString("white_username"),
                            rs.getString("black_username"),
                            rs.getString("winner_color"),
                            rs.getLong("ended_at")));
                }
            }
        }
        return results;
    }

    public static final class GameRecord {
        public final String whiteUsername;
        public final String blackUsername;
        public final String winnerColor;
        public final long endedAt;

        GameRecord(String whiteUsername, String blackUsername, String winnerColor, long endedAt) {
            this.whiteUsername = whiteUsername;
            this.blackUsername = blackUsername;
            this.winnerColor = winnerColor;
            this.endedAt = endedAt;
        }
    }

    public enum LoginResult { CREATED, OK, WRONG_PASSWORD }

    public synchronized LoginResult login(String username, String password) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT password_hash, salt, iterations FROM users WHERE username = ?")) {
            select.setString(1, username);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    String storedHash = rs.getString("password_hash");
                    String salt = rs.getString("salt");
                    int iterations = rs.getInt("iterations");
                    return hash(password, salt, iterations).equals(storedHash) ? LoginResult.OK : LoginResult.WRONG_PASSWORD;
                }
            }
        }

        String salt = newSalt();
        String hash = hash(password, salt, HASH_ITERATIONS);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO users (username, password_hash, salt, iterations, elo) VALUES (?, ?, ?, ?, " + STARTING_ELO + ")")) {
            insert.setString(1, username);
            insert.setString(2, hash);
            insert.setString(3, salt);
            insert.setInt(4, HASH_ITERATIONS);
            insert.executeUpdate();
        }
        return LoginResult.CREATED;
    }

    public synchronized int getElo(String username) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT elo FROM users WHERE username = ?")) {
            select.setString(1, username);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getInt("elo") : STARTING_ELO;
            }
        }
    }

    public synchronized void updateElo(String username, int newElo) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE users SET elo = ? WHERE username = ?")) {
            update.setInt(1, newElo);
            update.setString(2, username);
            update.executeUpdate();
        }
    }

    private static String newSalt() {
        byte[] saltBytes = new byte[16];
        new SecureRandom().nextBytes(saltBytes);
        return Base64.getEncoder().encodeToString(saltBytes);
    }

    // iterations=1 reproduces the original single-pass SHA-256(salt || password) exactly,
    // so rows created before this column existed still verify correctly.
    private static String hash(String password, String salt, int iterations) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] salted = salt.getBytes(StandardCharsets.UTF_8);
            byte[] data = password.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < iterations; i++) {
                digest.reset();
                digest.update(salted);
                digest.update(data);
                data = digest.digest();
            }
            return Base64.getEncoder().encodeToString(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
