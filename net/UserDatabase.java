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
// Passwords are salted-hashed with SHA-256 - never stored or compared in
// plain text. First login for a new username registers it; a known
// username must match its stored password.
public class UserDatabase implements AutoCloseable {
    private static final int STARTING_ELO = 1200;

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
                        "elo INTEGER NOT NULL DEFAULT " + STARTING_ELO + ")")) {
            create.execute();
        }
    }

    public enum LoginResult { CREATED, OK, WRONG_PASSWORD }

    public synchronized LoginResult login(String username, String password) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT password_hash, salt FROM users WHERE username = ?")) {
            select.setString(1, username);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    String storedHash = rs.getString("password_hash");
                    String salt = rs.getString("salt");
                    return hash(password, salt).equals(storedHash) ? LoginResult.OK : LoginResult.WRONG_PASSWORD;
                }
            }
        }

        String salt = newSalt();
        String hash = hash(password, salt);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO users (username, password_hash, salt, elo) VALUES (?, ?, ?, " + STARTING_ELO + ")")) {
            insert.setString(1, username);
            insert.setString(2, hash);
            insert.setString(3, salt);
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

    private static String hash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes());
            byte[] hashed = digest.digest(password.getBytes());
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
