package dropgoline.ui;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.SwingUtilities;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * 系統匣常駐圖示。右鍵選單用 JavaFX ContextMenu 繪製，
 * 因為 AWT 原生選單在中文 Windows 上會顯示成框框。
 */
public final class SystemTrayHelper {

    private SystemTrayHelper() {}

    private static TrayIcon trayIcon;
    private static Stage anchorStage;              // 隱形視窗，給 ContextMenu 當 owner
    private static volatile String currentId = "-";

    public static boolean install(Stage stage, String iconResourcePath, String tooltip) {
        if (!SystemTray.isSupported()) {
            System.out.println("[Tray] 此系統不支援系統匣");
            return false;
        }
        Platform.setImplicitExit(false);

        try {
            Image image = Toolkit.getDefaultToolkit().getImage(
                    SystemTrayHelper.class.getResource(iconResourcePath));

            // 不掛 AWT PopupMenu，改自己處理滑鼠事件
            trayIcon = new TrayIcon(image, tooltip);
            trayIcon.setImageAutoSize(true);

            trayIcon.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e)  { maybePopup(e); }
                @Override public void mouseReleased(MouseEvent e) { maybePopup(e); }
                @Override public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                        showStage(stage);                 // 雙擊左鍵 → 還原視窗
                    }
                }
                private void maybePopup(MouseEvent e) {
                    if (e.isPopupTrigger()) {             // 右鍵 → 彈出 JavaFX 選單
                        int x = e.getXOnScreen();
                        int y = e.getYOnScreen();
                        Platform.runLater(() -> showFxMenu(stage, x, y));
                    }
                }
            });

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
        currentId = (id == null || id.isBlank()) ? "-" : id;
    }

    // ===== 以下都在 JavaFX 執行緒上執行 =====

    private static void showFxMenu(Stage stage, double screenX, double screenY) {
        ensureAnchor();
        ContextMenu menu = buildMenu(stage);
        menu.show(anchorStage, screenX, screenY);
    }

    private static ContextMenu buildMenu(Stage stage) {
        MenuItem idItem = new MenuItem("ID: " + currentId);
        idItem.setDisable(true);

        MenuItem openItem = new MenuItem("開啟拖曳板");
        openItem.setOnAction(e -> showStage(stage));

        MenuItem closeItem = new MenuItem("關閉拖曳板");
        closeItem.setOnAction(e -> stage.hide());

        Menu settingsMenu = new Menu("設定");
        MenuItem openSettings = new MenuItem("其他設定…");
        openSettings.setOnAction(e -> new SettingsStage().show());
        settingsMenu.getItems().add(openSettings);

        MenuItem exitItem = new MenuItem("結束");
        exitItem.setOnAction(e -> {
            if (trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
            Platform.exit();
        });

        return new ContextMenu(
                idItem, new SeparatorMenuItem(),
                openItem, closeItem, settingsMenu,
                new SeparatorMenuItem(), exitItem);
    }

    /** 隱形的小視窗，純粹當 ContextMenu 的 owner（彈出選單需要一個 owner window）。 */
    private static void ensureAnchor() {
        if (anchorStage != null) return;
        anchorStage = new Stage();
        anchorStage.initStyle(StageStyle.UTILITY);   // 不顯示在工作列
        anchorStage.setOpacity(0);                    // 完全透明
        anchorStage.setWidth(1);
        anchorStage.setHeight(1);
        anchorStage.setX(-3000);                      // 移出畫面
        anchorStage.setY(-3000);
        anchorStage.setAlwaysOnTop(true);

        Scene scene = new Scene(new Pane(), 1, 1);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(
                SystemTrayHelper.class.getResource("/styles/app.css").toExternalForm());
        anchorStage.setScene(scene);
        anchorStage.show();
    }

    private static void showStage(Stage stage) {
        Platform.runLater(() -> {
            stage.show();
            stage.setIconified(false);
            stage.toFront();
        });
    }
}