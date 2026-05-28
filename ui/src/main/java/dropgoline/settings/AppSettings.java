package dropgoline.settings;

/**
 * 暫時版（mock）。等開發者 A 的真版本好了再換掉。
 * 真版本需要提供一樣的方法：current(), 各 getter/setter, save()
 */
public class AppSettings {

    // === Singleton ===
    private static AppSettings instance;

    public static AppSettings current() {
        if (instance == null) {
            instance = new AppSettings();
        }
        return instance;
    }

    private AppSettings() {
        // 私有建構子，外界不能 new，只能用 current()
    }

    // === 設定欄位（含預設值）===
    private String serverIP = "127.0.0.1";
    private String deviceName = "";
    private boolean autoClipboardCopy = false;
    private boolean autoClipboardSync = false;
    private boolean enableAutoReconnect = true;
    private boolean allowDiscovery = true;

    // === Getters / Setters ===
    public String getServerIP() { return serverIP; }
    public void setServerIP(String serverIP) { this.serverIP = serverIP; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public boolean isAutoClipboardCopy() { return autoClipboardCopy; }
    public void setAutoClipboardCopy(boolean v) { this.autoClipboardCopy = v; }

    public boolean isAutoClipboardSync() { return autoClipboardSync; }
    public void setAutoClipboardSync(boolean v) { this.autoClipboardSync = v; }

    public boolean isEnableAutoReconnect() { return enableAutoReconnect; }
    public void setEnableAutoReconnect(boolean v) { this.enableAutoReconnect = v; }

    public boolean isAllowDiscovery() { return allowDiscovery; }
    public void setAllowDiscovery(boolean v) { this.allowDiscovery = v; }

    // === 儲存（暫時版：只印出來，真版本會寫進檔案）===
    public void save() {
        System.out.println("[AppSettings] 儲存（暫時版，只印出）：");
        System.out.println("  serverIP=" + serverIP);
        System.out.println("  deviceName=" + deviceName);
        System.out.println("  autoClipboardCopy=" + autoClipboardCopy);
        System.out.println("  autoClipboardSync=" + autoClipboardSync);
        System.out.println("  enableAutoReconnect=" + enableAutoReconnect);
        System.out.println("  allowDiscovery=" + allowDiscovery);
    }
}