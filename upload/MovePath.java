import java.util.ArrayList;
import java.util.List;

public final class MovePath {
    private MovePath() {}

    // Cells a move passes through on its way to the destination, in travel order
    // (excludes the starting square, includes the destination). Knights jump instead
    // of sliding, so only their landing cell counts as part of the path.
    public static List<Position> cellsOf(PendingMove move) {
        int fr = move.getFromRow(), fc = move.getFromCol();
        int tr = move.getToRow(), tc = move.getToCol();
        int dr = tr - fr, dc = tc - fc;
        int distance = Math.max(Math.abs(dr), Math.abs(dc));

        List<Position> cells = new ArrayList<>();
        boolean isStraightLine = dr == 0 || dc == 0 || Math.abs(dr) == Math.abs(dc);
        if (!isStraightLine || distance == 0) {
            cells.add(new Position(tr, tc));
            return cells;
        }

        int rStep = Integer.compare(tr, fr);
        int cStep = Integer.compare(tc, fc);
        for (int i = 1; i <= distance; i++) {
            cells.add(new Position(fr + rStep * i, fc + cStep * i));
        }
        return cells;
    }
}
