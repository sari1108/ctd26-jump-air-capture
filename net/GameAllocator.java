import java.util.function.Supplier;

// The diagram's "Game Allocator": a separate concern from the Matchmaker (which only
// decides *who* plays whom) and from RoomRegistry (which only owns room codes/
// spectators) - this is the one place in the codebase that decides *where* a matched
// pair's game actually runs.
//
// Today there is exactly one Game Server shard - this process - so allocate() always
// means "start it right here." The seam is real anyway: MatchmakingServer and
// RoomRegistry both go through this class instead of constructing a Match directly,
// which is deliberate. The day this process becomes one of several game-hosting
// instances (Server_Design.md Q2/Q4), this is the one method that changes - picking a
// least-loaded remote shard, asking it to host the match, and telling both clients that
// shard's address to connect to - without either MatchmakingServer or RoomRegistry
// needing to change at all.
final class GameAllocator {
    private final UserDatabase userDatabase;
    private final ActivityLog log;
    private final RedisClient redis;

    GameAllocator(UserDatabase userDatabase, ActivityLog log) {
        this(userDatabase, log, null);
    }

    GameAllocator(UserDatabase userDatabase, ActivityLog log, RedisClient redis) {
        this.userDatabase = userDatabase;
        this.log = log;
        this.redis = redis;
    }

    Match allocate(Supplier<GameSession> sessionFactory, Match.Seat white, Match.Seat black) {
        Match match = new Match(sessionFactory.get(), userDatabase, log, white, black, redis);
        match.start();
        return match;
    }
}
