package dropgoline.settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.fasterxml.jackson.databind.ObjectMapper;

public class AppSettings {

    private static AppSettings instance;
    private static final Path FILE =
            Paths.get(System.getProperty("user.home"), ".dropgoline", "settings.json");

    // === 設定欄位 ===
    private String serverIP = "127.0.0.1";
    private String deviceName = "";
    private boolean autoClipboardCopy = false;
    private boolean autoClipboardSync = false;
    private boolean enableAutoReconnect = true;
    private boolean allowDiscovery = true;
    private String lastGroupCode = "";       // 給自動重連用

    public AppSettings() { }   // Jackson 需要無參數建構子

    public static synchronized AppSettings current() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static AppSettings load() {
        try {
            File f = FILE.toFile();
            if (f.exists()) {
                AppSettings loaded = new ObjectMapper().readValue(f, AppSettings.class);
                System.out.println("[Settings] 已讀取設定：" + FILE);
                return loaded;
            }
        } catch (IOException ex) {
            System.err.println("[Settings] 讀取失敗，使用預設：" + ex.getMessage());
        }
        return new AppSettings();
    }

    public void save() {
        try {
            File f = FILE.toFile();
            f.getParentFile().mkdirs();
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(f, this);
            System.out.println("[Settings] 已儲存：" + FILE);
        } catch (IOException ex) {
            System.err.println("[Settings] 儲存失敗：" + ex.getMessage());
        }
    }

    // === getters / setters（Jackson 與 SettingsStage 都會用到）===
    public String getServerIP() { return serverIP; }
    public void setServerIP(String v) { serverIP = v; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String v) { deviceName = v; }

    public boolean isAutoClipboardCopy() { return autoClipboardCopy; }
    public void setAutoClipboardCopy(boolean v) { autoClipboardCopy = v; }

    public boolean isAutoClipboardSync() { return autoClipboardSync; }
    public void setAutoClipboardSync(boolean v) { autoClipboardSync = v; }

    public boolean isEnableAutoReconnect() { return enableAutoReconnect; }
    public void setEnableAutoReconnect(boolean v) { enableAutoReconnect = v; }

    public boolean isAllowDiscovery() { return allowDiscovery; }
    public void setAllowDiscovery(boolean v) { allowDiscovery = v; }

    public String getLastGroupCode() { return lastGroupCode; }
    public void setLastGroupCode(String v) { lastGroupCode = v; }
}