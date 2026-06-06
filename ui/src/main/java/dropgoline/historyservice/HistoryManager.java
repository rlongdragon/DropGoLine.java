package dropgoline.historyservice;

import dropgoline.model.HistoryItem;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class HistoryManager {
    private static final String HISTORY_FILE = "history.txt";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static HistoryManager instance;

    private final ConcurrentHashMap<String, List<HistoryItem>> historyMap = new ConcurrentHashMap<>();

    private HistoryManager() {
        loadFromFile();
    }

    public static synchronized HistoryManager getInstance() {
        if (instance == null) {
            instance = new HistoryManager();
        }
        return instance;
    }

    public synchronized void addHistory(String target, String content, boolean isIncoming, String type) {
        HistoryItem item = new HistoryItem(LocalDateTime.now().format(FORMATTER), content, isIncoming, type);
        historyMap.computeIfAbsent(target, k -> Collections.synchronizedList(new ArrayList<>())).add(item);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(HISTORY_FILE, true))) {
            writer.write(target + '|' + item.getTimestamp() + '|' + item.getType() + '|'
                    + item.isIncoming() + '|' + item.getContent());
            writer.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized void addFileHistory(String target, String filePath, boolean isIncoming, String type) {
        addHistory(target, filePath, isIncoming, type);
    }

    public List<HistoryItem> getHistory(String target) {
        return new ArrayList<>(historyMap.getOrDefault(target, Collections.emptyList()));
    }

    private void loadFromFile() {
        File file = new File(HISTORY_FILE);
        if (!file.exists()) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|", 5);
                if (parts.length == 5) {
                    HistoryItem item = new HistoryItem(parts[1], parts[4], Boolean.parseBoolean(parts[3]), parts[2]);
                    historyMap.computeIfAbsent(parts[0], k -> Collections.synchronizedList(new ArrayList<>())).add(item);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
