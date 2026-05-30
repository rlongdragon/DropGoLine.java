package dropgoline.ui;

import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.Image;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;

import javafx.application.Platform;
import javafx.stage.Stage;

/** 系統匣（通知區域）常駐圖示與右鍵選單。 */
public final class SystemTrayHelper {

    private SystemTrayHelper() {}

    private static TrayIcon trayIcon;
    private static MenuItem idItem;   // 顯示目前 ID，需要時更新

    public static boolean install(Stage stage, String iconResourcePath, String tooltip) {
        if (!SystemTray.isSupported()) {
            System.out.println("[Tray] 此系統不支援系統匣");
            return false;
        }
        // 關掉「關閉視窗就結束 JVM」，才能縮到匣裡
        Platform.setImplicitExit(false);

        try {
            Image image = Toolkit.getDefaultToolkit().getImage(
                    SystemTrayHelper.class.getResource(iconResourcePath));

            PopupMenu popup = new PopupMenu();

            // --- ID 顯示（不可點，當標題用）---
            idItem = new MenuItem("ID: -");
            idItem.setEnabled(false);
            popup.add(idItem);
            popup.addSeparator();

            // --- 開啟 / 關閉拖曳板 ---
            MenuItem openItem = new MenuItem("開啟拖曳板");
            openItem.addActionListener(e -> showStage(stage));

            MenuItem closeItem = new MenuItem("關閉拖曳板");
            closeItem.addActionListener(e -> Platform.runLater(stage::hide));

            // --- 設定（子選單，會顯示 ▶）---
            Menu settingsMenu = new Menu("設定");
            MenuItem openSettings = new MenuItem("其他設定…");
            openSettings.addActionListener(e ->
                    Platform.runLater(() -> new SettingsStage().show()));
            settingsMenu.add(openSettings);

            // --- 結束 ---
            MenuItem exitItem = new MenuItem("結束");
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
            trayIcon.addActionListener(e -> showStage(stage));   // 雙擊圖示 → 還原

            SystemTray.getSystemTray().add(trayIcon);
            System.out.println("[Tray] 系統匣圖示已建立");
            return true;

        } catch (AWTException | RuntimeException ex) {
            System.err.println("[Tray] 建立失敗：" + ex.getMessage());
            return false;
        }
    }

    /** 更新選單裡顯示的 ID（由 MainStage.onIdChanged 呼叫）。 */
    public static void updateId(String id) {
        if (idItem != null) {
            EventQueue.invokeLater(() -> idItem.setLabel("ID: " + id));
        }
    }

    private static void showStage(Stage stage) {
        Platform.runLater(() -> {
            stage.show();
            stage.setIconified(false);
            stage.toFront();
        });
    }
}