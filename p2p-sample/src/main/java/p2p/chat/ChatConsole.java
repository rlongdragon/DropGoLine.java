package p2p.chat;

final class ChatConsole {
    private static final boolean COLOR = System.getenv("NO_COLOR") == null;
    private static final String CLEAR_LINE = color("\r\033[2K");
    private static final String RESET = color("\033[0m");
    private static final String CYAN = color("\033[36m");
    private static final String GREEN = color("\033[32m");
    private static final String PURPLE = color("\033[35m");
    private static final String YELLOW = color("\033[33m");
    private static final String RED = color("\033[31m");
    private static final String DIM = color("\033[2m");
    private static boolean promptVisible;
    private static String promptMode = "message";

    private ChatConsole() {
    }

    static synchronized void prompt() {
        if (promptVisible) {
            return;
        }
        System.out.print(GREEN + promptMode + " <<< " + RESET);
        System.out.flush();
        promptVisible = true;
    }

    static synchronized void commandMode() {
        promptMode = "command";
        if (promptVisible) {
            redrawPrompt();
        }
    }

    static synchronized void messageMode() {
        promptMode = "message";
        if (promptVisible) {
            redrawPrompt();
        }
    }

    static synchronized void inputMode(boolean command) {
        promptMode = command ? "command" : "message";
    }

    static synchronized void redrawInput(String line, int cursor) {
        System.out.print(CLEAR_LINE);
        printPrompt();
        System.out.print(line);
        int moveLeft = line.length() - cursor;
        if (moveLeft > 0) {
            System.out.print("\033[" + moveLeft + "D");
        }
        System.out.flush();
    }

    static synchronized void incoming(String username, String message) {
        event(CYAN + username + " >>> " + RESET + message);
    }

    static synchronized void relay(String username, String message) {
        event(YELLOW + username + " >>> " + RESET + message + DIM + " (relay)" + RESET);
    }

    static synchronized void privateRelay(String username, String message) {
        event(PURPLE + username + " >>> " + RESET + message + DIM + " (private relay)" + RESET);
    }

    static synchronized void file(String message) {
        event(YELLOW + "[file] " + RESET + message);
    }

    static synchronized void system(String message) {
        event(DIM + "[system] " + message + RESET);
    }

    static synchronized void error(String message) {
        event(RED + "[error] " + RESET + message);
    }

    static synchronized void notice(String message) {
        event(YELLOW + "[notice] " + RESET + message);
    }

    static synchronized void help() {
        event(YELLOW + "[help] " + RESET + "commands:");
        event("  /help                 show commands");
        event("  /msg <user> <text>    send private message");
        event("  /file <path> [user]   offer file to everyone or one user");
        event("  /save <offer-id>      download offered file");
        event("  /quit                 leave chat");
    }

    static synchronized void acceptedInput() {
        promptVisible = false;
        promptMode = "message";
    }

    private static void event(String message) {
        if (promptVisible) {
            System.out.print(CLEAR_LINE);
        }
        System.out.println(message);
        if (promptVisible) {
            printPrompt();
        }
    }

    private static String color(String value) {
        return COLOR ? value : "";
    }

    private static void redrawPrompt() {
        System.out.print(CLEAR_LINE);
        printPrompt();
    }

    private static void printPrompt() {
        if ("command".equals(promptMode)) {
            System.out.print(PURPLE + "command === " + RESET);
        } else {
            System.out.print(GREEN + "message <<< " + RESET);
        }
        System.out.flush();
    }
}
