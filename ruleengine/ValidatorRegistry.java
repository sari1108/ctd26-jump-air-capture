import java.util.HashMap;
import java.util.Map;

public final class ValidatorRegistry {
    private ValidatorRegistry() {}

    public static Map<PieceType, MoveValidator> standard() {
        Map<PieceType, MoveValidator> map = new HashMap<>();
        map.put(PieceType.ROOK, new RookValidator());
        map.put(PieceType.BISHOP, new BishopValidator());
        map.put(PieceType.KNIGHT, new KnightValidator());
        map.put(PieceType.QUEEN, new QueenValidator());
        map.put(PieceType.KING, new KingValidator());
        map.put(PieceType.PAWN, new PawnValidator());
        return map;
    }
}
