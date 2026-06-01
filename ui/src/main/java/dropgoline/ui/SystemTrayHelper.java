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

public final class SystemTrayHelper {

    private SystemTrayHelper() {
    }

    private static TrayIcon trayIcon;
    private static Stage anchorStage;
    private static volatile String currentId = "-";
    private static Runnable onConnect;
    private static Runnable onDisconnect;

    public static boolean install(Stage stage, String iconResourcePath, String tooltip, Runnable connectAction,
                                  Runnable disconnectAction) {
        onConnect = connectAction;
        onDisconnect = disconnectAction;

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
                @Override
                public void mousePressed(MouseEvent e) {
                    maybePopup(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    maybePopup(e);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                        showStage(stage); // 雙擊左鍵 → 還原視窗
                    }
                }

                private void maybePopup(MouseEvent e) {
                    if (e.isPopupTrigger()) { // 右鍵 → 彈出 JavaFX 選單
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

    public static void updateId(String id) {
        currentId = (id == null || id.isBlank()) ? "-" : id;
    }

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

        MenuItem connectItem = new MenuItem("建立連線");
        connectItem.setOnAction(e -> {
            if (onConnect != null)
                onConnect.run();
        });

        MenuItem disconnectItem = new MenuItem("斷開連線");
        disconnectItem.setOnAction(e -> {
            if (onDisconnect != null)
                onDisconnect.run();
        });

        MenuItem openSettings = new MenuItem("其他設定…");
        openSettings.setOnAction(e -> new SettingsStage().show());

        settingsMenu.getItems().addAll(
                connectItem, disconnectItem, new SeparatorMenuItem(), openSettings);

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

    private static void ensureAnchor() {
        if (anchorStage != null)
            return;
        anchorStage = new Stage();
        anchorStage.initStyle(StageStyle.UTILITY); // 不顯示在工作列
        anchorStage.setOpacity(0); // 完全透明
        anchorStage.setWidth(1);
        anchorStage.setHeight(1);
        anchorStage.setX(-3000); // 移出畫面
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