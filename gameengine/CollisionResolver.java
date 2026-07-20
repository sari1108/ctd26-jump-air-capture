import java.util.List;

public final class CollisionResolver {
    private CollisionResolver() {}

    // A move already in flight always has right of way over one just being queued.
    // Walking the new move's path cell by cell, the first pending move (of either
    // color) that also touches a cell decides the outcome: a same-color piece blocks
    // it (it stops one cell short), a different-color piece eats it outright (the new
    // move never happens at all - the piece stays put and gets captured when the
    // piece already in flight arrives).
    public static PendingMove resolve(PendingMove incoming, List<PendingMove> inFlight) {
        List<Position> path = MovePath.cellsOf(incoming);
        PieceColor color = incoming.getPiece().getColor();

        for (int i = 0; i < path.size(); i++) {
            Position cell = path.get(i);
            boolean blockedBySameColor = false;
            for (PendingMove existing : inFlight) {
                if (!MovePath.cellsOf(existing).contains(cell)) continue;
                if (existing.getPiece().getColor() != color) return null;
                blockedBySameColor = true;
            }
            if (blockedBySameColor) {
                if (i == 0) {
                    return incoming.truncateTo(incoming.getFromRow(), incoming.getFromCol(), incoming.getStartTime());
                }
                Position stopAt = path.get(i - 1);
                long arrival = incoming.getStartTime() + (long) i * GameConfig.MOVE_DURATION_MS;
                return incoming.truncateTo(stopAt.getRow(), stopAt.getCol(), arrival);
            }
        }
        return incoming;
    }
}
