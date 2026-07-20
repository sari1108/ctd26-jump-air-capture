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

// Server-side user store: username + password + ELO, persisted in SQLite
// (per the course's "save at SQLite db on server side" requirement).
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

    public UserDatabase(String dbFilePath) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on classpath (expected lib/sqlite-jdbc.jar)", e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFilePath);
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
