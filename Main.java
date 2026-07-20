import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        StringBuilder sb = new StringBuilder();
        
        boolean inside = false;
        while (sc.hasNextLine()) {
            String line = sc.nextLine().trim();
            if (line.equals("Board:")) { inside = true; continue; }
            if (line.equals("Commands:")) break; 
            if (inside && !line.isEmpty()) sb.append(line).append("\n");
        }

        try {
            Board board = new BoardParser().parse(sb.toString());
            GameSession game = new GameSession(board);

            while (sc.hasNextLine()) {
                String input = sc.nextLine().trim();
                if (input.isEmpty()) continue;
                String[] cmd = input.split(" ");
                
                switch (cmd[0]) {
                    case "click":
                        game.click(Integer.parseInt(cmd[1]), Integer.parseInt(cmd[2]));
                        break;
                    case "wait":
                        game.waitMs(Long.parseLong(cmd[1]));
                        break;
                    case "jump":
                        int jr = Integer.parseInt(cmd[2]) / GameConfig.CELL_SIZE;
                        int jc = Integer.parseInt(cmd[1]) / GameConfig.CELL_SIZE;
                        game.activateJump(jr, jc);
                        break;
                    case "print":
                        new BoardPrinter().print(game.getBoard());
                        break;
                }
            }
        } catch (Exception e) {
            System.out.print(e.getMessage());
        }
    }
}