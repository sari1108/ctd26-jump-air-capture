import java.util.Objects;

public class Position {
    private int r, c;

    public Position(int r, int c) {
        this.r = r;
        this.c = c;
    }

    public int getRow() { return r; }
    public int getCol() { return c; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Position position = (Position) o;
        return r == position.r && c == position.c;
    }

    @Override
    public int hashCode() {
        return Objects.hash(r, c);
    }
}