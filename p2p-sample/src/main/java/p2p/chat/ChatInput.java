package p2p.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Scanner;

final class ChatInput implements AutoCloseable {
    private final Scanner fallback;
    private final String savedTtyState;
    private final boolean rawMode;

    ChatInput(Scanner fallback) {
        this.fallback = fallback;
        this.savedTtyState = readTtyState();
        this.rawMode = savedTtyState != null && setTtyMode("raw -echo min 1 time 0");
    }

    String readLine() throws IOException {
        if (!rawMode) {
            return fallback.hasNextLine() ? fallback.nextLine() : null;
        }

        StringBuilder line = new StringBuilder();
        boolean commandMode = false;
        while (true) {
            int read = System.in.read();
            if (read < 0) {
                return null;
            }
            char c = (char) read;
            if (c == '\r' || c == '\n') {
                System.out.println();
                return line.toString();
            }
            if (c == 3) {
                System.out.println();
                return "/quit";
            }
            if (c == 127 || c == '\b') {
                if (!line.isEmpty()) {
                    line.deleteCharAt(line.length() - 1);
                    System.out.print("\b \b");
                    if (line.isEmpty() && commandMode) {
                        commandMode = false;
                        ChatConsole.messageMode();
                    }
                }
                continue;
            }
            if (Character.isISOControl(c)) {
                continue;
            }
            line.append(c);
            if (line.length() == 1 && c == '/') {
                commandMode = true;
                ChatConsole.commandMode();
            }
            System.out.print(c);
            System.out.flush();
        }
    }

    @Override
    public void close() {
        if (rawMode) {
            setTtyMode(savedTtyState);
        }
    }

    private static String readTtyState() {
        ProcessBuilder processBuilder = new ProcessBuilder("sh", "-c", "stty -g < /dev/tty");
        try {
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String value = reader.readLine();
                if (process.waitFor() == 0 && value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean setTtyMode(String mode) {
        ProcessBuilder processBuilder = new ProcessBuilder("sh", "-c", "stty " + mode + " < /dev/tty");
        try {
            return processBuilder.start().waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
