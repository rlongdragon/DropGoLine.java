package dropgoline.ui;

import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javafx.application.Platform;
import javafx.stage.Stage;

/** 系統匣常駐圖示與右鍵選單。 */
public final class SystemTrayHelper {

    private SystemTrayHelper() {}

    private static TrayIcon trayIcon;
    private static MenuItem idItem;
    private static final Font MENU_FONT = pickMenuFont();   // 有中文字符的字體

    public static boolean install(Stage stage, String iconResourcePath, String tooltip) {
        if (!SystemTray.isSupported()) {
            System.out.println("[Tray] 此系統不支援系統匣");
            return false;
        }
        Platform.setImplicitExit(false);

        try {
            Image image = Toolkit.getDefaultToolkit().getImage(
                    SystemTrayHelper.class.getResource(iconResourcePath));

            PopupMenu popup = new PopupMenu();
            popup.setFont(MENU_FONT);

            // ID 顯示（不可點）
            idItem = item("ID: -");
            idItem.setEnabled(false);
            popup.add(idItem);
            popup.addSeparator();

            MenuItem openItem = item("開啟拖曳板");
            openItem.addActionListener(e -> showStage(stage));

            MenuItem closeItem = item("關閉拖曳板");
            closeItem.addActionListener(e -> Platform.runLater(stage::hide));

            Menu settingsMenu = new Menu("設定");
            settingsMenu.setFont(MENU_FONT);
            MenuItem openSettings = item("其他設定…");
            openSettings.addActionListener(e ->
                    Platform.runLater(() -> new SettingsStage().show()));
            settingsMenu.add(openSettings);

            MenuItem exitItem = item("結束");
            exitItem.addActionListener(e -> {
                SystemTray.getSystemTray().remove(trayIcon);
                Platform.runLater(Platform::exit);
            });

            popup.add(openItem);
            popup.add(closeItem);
            popup.add(settingsMenu);
            popup.addSeparator();
            popup.add(exitItem);

            trayIcon = new TrayIcon(image, tooltip, popup);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> showStage(stage));

            SystemTray.getSystemTray().add(trayIcon);
            System.out.println("[Tray] 系統匣已建立，選單字體 = " + MENU_FONT.getFamily());
            return true;

        } catch (AWTException | RuntimeException ex) {
            System.err.println("[Tray] 建立失敗：" + ex.getMessage());
            return false;
        }
    }

    public static void updateId(String id) {
        if (idItem != null) {
            EventQueue.invokeLater(() -> idItem.setLabel("ID: " + id));
        }
    }

    /** 建立已套用中文字體的 MenuItem */
    private static MenuItem item(String label) {
        MenuItem mi = new MenuItem(label);
        mi.setFont(MENU_FONT);
        return mi;
    }

    /** 從候選清單挑第一個系統有裝、能顯示中文的字體 */
    private static Font pickMenuFont() {
        String[] candidates = {
            "Microsoft JhengHei",   // 微軟正黑體（繁中，Windows 標配）
            "Microsoft YaHei",      // 微軟雅黑（簡中）
            "PMingLiU", "MingLiU",  // 新細明體 / 細明體
            "SimSun"
        };
        Set<String> available = new HashSet<>(Arrays.asList(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String name : candidates) {
            if (available.contains(name)) {
                return new Font(name, Font.PLAIN, 12);
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 12);   // 都沒有才退回預設
    }

    private static void showStage(Stage stage) {
        Platform.runLater(() -> {
            stage.show();
            stage.setIconified(false);
            stage.toFront();
        });
    }
}