package sample.chat;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

final class ChatInput implements AutoCloseable {
    private static final int MAX_HISTORY = 10;
    private final Scanner fallback;
    private final String savedTtyState;
    private final InputStream input;
    private final boolean rawMode;
    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;

    ChatInput(Scanner fallback) {
        this.fallback = fallback;
        this.savedTtyState = readTtyState();
        this.input = openTtyInput();
        this.rawMode = input != null && savedTtyState != null && setTtyMode("-icanon -echo min 1 time 0");
    }

    String readLine() throws IOException {
        if (!rawMode) {
            return fallback.hasNextLine() ? fallback.nextLine() : null;
        }

        StringBuilder line = new StringBuilder();
        int cursor = 0;
        String draft = "";
        while (true) {
            int read = input.read();
            if (read < 0) {
                return null;
            }
            char c = (char) read;
            if (c == '\r' || c == '\n') {
                System.out.print("\r\n");
                System.out.flush();
                String value = line.toString();
                remember(value);
                return value;
            }
            if (c == 3) {
                System.out.print("\r\n");
                System.out.flush();
                return "/quit";
            }
            if (c == 1) {
                cursor = 0;
                redraw(line, cursor);
                continue;
            }
            if (c == 5) {
                cursor = line.length();
                redraw(line, cursor);
                continue;
            }
            if (c == 27) {
                EscapeKey key = readEscapeKey();
                if (key == EscapeKey.UP) {
                    if (!history.isEmpty()) {
                        if (historyIndex < 0) {
                            draft = line.toString();
                            historyIndex = history.size() - 1;
                        } else if (historyIndex > 0) {
                            historyIndex--;
                        }
                        replaceLine(line, history.get(historyIndex));
                        cursor = line.length();
                        redraw(line, cursor);
                    }
                    continue;
                }
                if (key == EscapeKey.DOWN) {
                    if (historyIndex >= 0) {
                        if (historyIndex < history.size() - 1) {
                            historyIndex++;
                            replaceLine(line, history.get(historyIndex));
                        } else {
                            historyIndex = -1;
                            replaceLine(line, draft);
                            draft = "";
                        }
                        cursor = line.length();
                        redraw(line, cursor);
                    }
                    continue;
                }
                if (key == EscapeKey.LEFT) {
                    if (cursor > 0) {
                        cursor--;
                        redraw(line, cursor);
                    }
                    continue;
                }
                if (key == EscapeKey.RIGHT) {
                    if (cursor < line.length()) {
                        cursor++;
                        redraw(line, cursor);
                    }
                    continue;
                }
                if (key == EscapeKey.HOME) {
                    cursor = 0;
                    redraw(line, cursor);
                    continue;
                }
                if (key == EscapeKey.END) {
                    cursor = line.length();
                    redraw(line, cursor);
                    continue;
                }
                if (key == EscapeKey.DELETE) {
                    if (cursor < line.length()) {
                        line.deleteCharAt(cursor);
                        historyIndex = -1;
                        redraw(line, cursor);
                    }
                    continue;
                }
                continue;
            }
            if (c == 127 || c == '\b') {
                if (cursor > 0) {
                    line.deleteCharAt(cursor - 1);
                    cursor--;
                    historyIndex = -1;
                    redraw(line, cursor);
                }
                continue;
            }
            if (Character.isISOControl(c)) {
                continue;
            }
            line.insert(cursor, c);
            cursor++;
            historyIndex = -1;
            redraw(line, cursor);
        }
    }

    @Override
    public void close() {
        if (rawMode) {
            setTtyMode(savedTtyState);
        }
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static InputStream openTtyInput() {
        try {
            return new FileInputStream("/dev/tty");
        } catch (IOException ignored) {
            return null;
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

    private void redraw(StringBuilder line, int cursor) {
        ChatConsole.inputMode(!line.isEmpty() && line.charAt(0) == '/');
        ChatConsole.redrawInput(line.toString(), cursor);
    }

    private void remember(String value) {
        historyIndex = -1;
        if (value == null || value.isBlank()) {
            return;
        }
        if (!history.isEmpty() && history.get(history.size() - 1).equals(value)) {
            return;
        }
        history.add(value);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    private void replaceLine(StringBuilder line, String value) {
        line.setLength(0);
        line.append(value);
    }

    private EscapeKey readEscapeKey() throws IOException {
        int first = input.read();
        if (first != '[' && first != 'O') {
            return EscapeKey.UNKNOWN;
        }
        int second = input.read();
        if (second < 0) {
            return EscapeKey.UNKNOWN;
        }
        return switch ((char) second) {
            case 'A' -> EscapeKey.UP;
            case 'B' -> EscapeKey.DOWN;
            case 'C' -> EscapeKey.RIGHT;
            case 'D' -> EscapeKey.LEFT;
            case 'H' -> EscapeKey.HOME;
            case 'F' -> EscapeKey.END;
            case '1', '7' -> readTildeKey(EscapeKey.HOME);
            case '3' -> readTildeKey(EscapeKey.DELETE);
            case '4', '8' -> readTildeKey(EscapeKey.END);
            default -> EscapeKey.UNKNOWN;
        };
    }

    private EscapeKey readTildeKey(EscapeKey key) throws IOException {
        int next = input.read();
        return next == '~' ? key : EscapeKey.UNKNOWN;
    }

    private enum EscapeKey {
        UP,
        DOWN,
        LEFT,
        RIGHT,
        HOME,
        END,
        DELETE,
        UNKNOWN
    }
}
