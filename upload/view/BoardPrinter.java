public class BoardPrinter {
    public void print(BoardView b) {
        for (int i = 0; i < b.getRows(); i++) {
            for (int j = 0; j < b.getCols(); j++) {
                System.out.print(b.getPieceAt(i, j).getSymbol() + (j == b.getCols() - 1 ? "" : " "));
            }
            System.out.println();
        }
    }
}