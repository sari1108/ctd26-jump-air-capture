import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

// Private-room matchmaking, parallel to MatchmakingServer's ELO queue: one player
// creates a room and gets back a short shareable code; whoever next joins with that
// code becomes their opponent (Black) and the match starts; anyone who joins after
// that is attached as a read-only spectator to the Match already in progress.
//
// `rooms` (the live connections/Match objects) always stays local - it can't be
// anything else, the same reason MatchmakingServer's connections can't move to Redis.
// When a RedisClient is supplied, room *state* (does this code exist, is it still
// waiting or already started) is additionally mirrored into Redis, so - same as the
// matchmaking queue - it's inspectable as real shared state, not just process memory.
final class RoomRegistry {
    // No 0/O/1/I - avoids codes that are ambiguous to read aloud or type.
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;

    private final ActivityLog log;
    private final Supplier<GameSession> sessionFactory;
    private final RedisClient redis;
    private final GameAllocator gameAllocator;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    RoomRegistry(UserDatabase userDatabase, ActivityLog log, Supplier<GameSession> sessionFactory) {
        this(log, sessionFactory, null, new GameAllocator(userDatabase, log));
    }

    RoomRegistry(UserDatabase userDatabase, ActivityLog log, Supplier<GameSession> sessionFactory,
                 RedisClient redis, GameAllocator gameAllocator) {
        this(log, sessionFactory, redis, gameAllocator);
    }

    private RoomRegistry(ActivityLog log, Supplier<GameSession> sessionFactory,
                          RedisClient redis, GameAllocator gameAllocator) {
        this.log = log;
        this.sessionFactory = sessionFactory;
        this.redis = redis;
        this.gameAllocator = gameAllocator;
    }

    private static String redisRoomKey(String code) {
        return "room:" + code;
    }

    private void mirrorToRedis(String code, String field, String value) {
        if (redis == null) return;
        try {
            redis.hset(redisRoomKey(code), field, value);
        } catch (IOException e) {
            log.log("Redis error updating room " + code + ": " + e.getMessage());
        }
    }

    // Blocks the creator's thread until a second player joins and the match starts -
    // same handoff pattern as MatchmakingServer's ELO queue wait.
    void create(WebSocketConnection connection, String username, int elo) {
        String code = newUniqueCode();
        Room room = new Room(connection, username, elo);
        rooms.put(code, room);
        mirrorToRedis(code, "creatorUsername", username);
        mirrorToRedis(code, "creatorElo", String.valueOf(elo));
        mirrorToRedis(code, "state", "waiting");
        log.log(username + " created room " + code);
        sendSafely(connection, "ROOM_CREATED " + code);
        try {
            room.matchStarted.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Returns true if ownership of this connection was handed off (it became the
    // Black seat of a fresh Match, or was attached as a spectator) - the caller
    // should stop reading from the connection itself either way. Returns false only
    // when the room code doesn't exist, in which case the caller keeps the connection.
    boolean join(String code, WebSocketConnection connection, String username, int elo) {
        Room room = rooms.get(code);
        if (room == null) {
            sendSafely(connection, "ROOM_NOT_FOUND " + code);
            return false;
        }

        synchronized (room) {
            if (room.match == null) {
                Match.Seat white = new Match.Seat(room.creatorConnection, room.creatorUsername, "WHITE");
                Match.Seat black = new Match.Seat(connection, username, "BLACK");

                sendSafely(room.creatorConnection, "MATCH_FOUND WHITE " + username + " " + elo);
                sendSafely(connection, "MATCH_FOUND BLACK " + room.creatorUsername + " " + room.creatorElo);

                room.match = gameAllocator.allocate(sessionFactory, white, black);
                mirrorToRedis(code, "state", "started");
                log.log("Room " + code + " match started: " + room.creatorUsername + " (white) vs " + username + " (black)");
                room.matchStarted.countDown();
            } else {
                sendSafely(connection, "SPECTATE_JOINED " + code + " "
                        + room.match.getWhiteUsername() + " " + room.match.getBlackUsername());
                room.match.addSpectator(connection, username);
                log.log(username + " is spectating room " + code);
            }
            return true;
        }
    }

    int roomCount() {
        return rooms.size();
    }

    private String newUniqueCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            code = sb.toString();
        } while (rooms.containsKey(code));
        return code;
    }

    private void sendSafely(WebSocketConnection connection, String text) {
        try {
            connection.sendText(text);
        } catch (IOException ignored) {
        }
    }

    private static final class Room {
        final WebSocketConnection creatorConnection;
        final String creatorUsername;
        final int creatorElo;
        final CountDownLatch matchStarted = new CountDownLatch(1);
        volatile Match match;

        Room(WebSocketConnection connection, String username, int elo) {
            this.creatorConnection = connection;
            this.creatorUsername = username;
            this.creatorElo = elo;
        }
    }
}
