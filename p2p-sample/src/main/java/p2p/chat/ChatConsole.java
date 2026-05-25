package p2p.chat;

final class ChatConsole {
    private static final boolean COLOR = System.getenv("NO_COLOR") == null;
    private static final String RESET = color("\033[0m");
    private static final String CYAN = color("\033[36m");
    private static final String GREEN = color("\033[32m");
    private static final String YELLOW = color("\033[33m");
    private static final String RED = color("\033[31m");
    private static final String DIM = color("\033[2m");

    private ChatConsole() {
    }

    static synchronized void prompt() {
        System.out.print(GREEN + "message <<< " + RESET);
        System.out.flush();
    }

    static synchronized void incoming(String username, String message) {
        System.out.println(CYAN + username + " >>> " + RESET + message);
    }

    static synchronized void relay(String username, String message) {
        System.out.println(YELLOW + username + " >>> " + RESET + message + DIM + " (relay)" + RESET);
    }

    static synchronized void file(String message) {
        System.out.println(YELLOW + "[file] " + RESET + message);
    }

    static synchronized void system(String message) {
        System.out.println(DIM + "[system] " + message + RESET);
    }

    static synchronized void error(String message) {
        System.out.println(RED + "[error] " + RESET + message);
    }

    static synchronized void notice(String message) {
        System.out.println(YELLOW + "[notice] " + RESET + message);
    }

    private static String color(String value) {
        return COLOR ? value : "";
    }
}
