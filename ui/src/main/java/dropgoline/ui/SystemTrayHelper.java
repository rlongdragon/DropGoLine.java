package dropgoline.ui;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;

import javafx.application.Platform;
import javafx.stage.Stage;

/** 系統匣（通知區域）常駐圖示。Windows 用 AWT SystemTray 實作。 */
public final class SystemTrayHelper {

    private SystemTrayHelper() {}

    private static TrayIcon trayIcon;

    /**
     * 安裝系統匣圖示。
     * @return 成功安裝回 true；系統不支援或失敗回 false
     */
    public static boolean install(Stage stage, String iconResourcePath, String tooltip) {
        if (!SystemTray.isSupported()) {
            System.out.println("[Tray] 此系統不支援系統匣");
            return false;
        }

        // 關掉「關閉最後一個視窗就結束 JVM」，否則縮到匣裡程式會直接死掉
        Platform.setImplicitExit(false);

        try {
            Image image = Toolkit.getDefaultToolkit().getImage(
                    SystemTrayHelper.class.getResource(iconResourcePath));

            // 右鍵選單（AWT 的 PopupMenu）
            PopupMenu popup = new PopupMenu();

            MenuItem showItem = new MenuItem("顯示主視窗");
            showItem.addActionListener(e -> showStage(stage));

            MenuItem exitItem = new MenuItem("結束");
            exitItem.addActionListener(e -> {
                SystemTray.getSystemTray().remove(trayIcon);
                Platform.runLater(Platform::exit);
            });

            popup.add(showItem);
            popup.addSeparator();
            popup.add(exitItem);

            trayIcon = new TrayIcon(image, tooltip, popup);
            trayIcon.setImageAutoSize(true);
            // 雙擊圖示 → 還原視窗
            trayIcon.addActionListener(e -> showStage(stage));

            SystemTray.getSystemTray().add(trayIcon);
            System.out.println("[Tray] 系統匣圖示已建立");
            return true;

        } catch (AWTException | RuntimeException ex) {
            System.err.println("[Tray] 建立失敗：" + ex.getMessage());
            return false;
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