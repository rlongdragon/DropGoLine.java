package dropgoline.historyservice;

import dropgoline.settings.AppSettings;
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
    private static HistoryManager instance;

    // 使用 ConcurrentHashMap 確保多執行緒安全
    private final ConcurrentHashMap<String, List<HistoryItem>> historyMap = new ConcurrentHashMap<String,List<HistoryItem>>();
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private HistoryManager() {loadFromFile();}

    public static synchronized HistoryManager getInstance() {
        if (instance == null) {
            instance = new HistoryManager();
        }
        return instance;
    }

    /**
     * 🌟 核心：接受 3 個參數的 addHistory
     */
    public synchronized void addHistory(String target, String content, boolean isIncoming,String type) {
        System.out.println("[DEBUG] 正在存入紀錄, Target: " + target + ", Content: " + content);
        HistoryItem item = new HistoryItem(
            java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            content, 
            isIncoming,
            type
        );
        
        // 存入記憶體快取
        historyMap.computeIfAbsent(target, k -> Collections.synchronizedList(new ArrayList<>())).add(item);

        // 同步寫入本地文字檔 history.txt
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(HISTORY_FILE, true))) {
            String direction = isIncoming ? "【接收】" : "【發送】";
            writer.write(target + '|' + item.getTimestamp() + '|' + item.getType() + '|' + item.isIncoming() + '|' + item.getContent());
            writer.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    
    public synchronized void addFileHistory(String target, String filePath, boolean isIncoming, String type) {
        HistoryItem item = new HistoryItem(LocalDateTime.now().format(formatter), filePath, isIncoming, type);
        historyMap.computeIfAbsent(target, k -> Collections.synchronizedList(new ArrayList<>())).add(item);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(HISTORY_FILE, true))) {
            String direction = isIncoming ? "【接收】" : "【發送】";
            writer.write(item.getTimestamp() + " " + direction + " " + target + " [" + type + "]: " + filePath);
            writer.newLine();
        } catch (IOException e) {
            System.err.println("[錯誤] 寫入檔案歷史紀錄失敗: " + e.getMessage());
        }
    }

    /**
     * 🌟 核心：回傳明確的 List<HistoryItem>，讓 P2pDebugConsole 的 for 迴圈有資料可以跑
     */
    public List<HistoryItem> getHistory(String target) {
        return new ArrayList<>(historyMap.getOrDefault(target, Collections.emptyList()));
    }

    /**
     * 歷史紀錄資料結構
     */
    // public static class HistoryItem {
    //     private final String timestamp;
    //     private final String content;
    //     private final boolean isIncoming;
    //     private final String type;

    //     public HistoryItem(String timestamp, String content, boolean isIncoming) {
    //         this(timestamp, content, isIncoming, "TEXT"); 
    //     }

    //     public HistoryItem(String timestamp, String content, boolean isIncoming,String type) {
    //         this.timestamp = timestamp;
    //         this.content = content;
    //         this.isIncoming = isIncoming;
    //         this.type = type;   
    //     }

    //     public String getTimestamp() { return timestamp; }
    //     public String getContent() { return content; }
    //     public boolean isIncoming() { return isIncoming; }
    //     public String getType() { return type; } 
    // }

    private void loadFromFile() {
        File file = new File(HISTORY_FILE);
        if (!file.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|", 5);
                if (parts.length == 5) {
                    String target = parts[0];
                    HistoryItem item = new HistoryItem(parts[1], parts[4], Boolean.parseBoolean(parts[3]), parts[2]);
                    historyMap.computeIfAbsent(target, k -> Collections.synchronizedList(new ArrayList<>())).add(item);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}