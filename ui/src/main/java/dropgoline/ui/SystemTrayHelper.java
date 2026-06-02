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
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/** 系統匣常駐圖示。右鍵選單用「可聚焦的小視窗」實作，點擊與 hover 才會正常。 */
public final class SystemTrayHelper {

    private SystemTrayHelper() {}

    private static TrayIcon trayIcon;
    private static Stage menuStage;
    private static volatile String currentId = "-";
    private static Runnable onConnect;
    private static Runnable onDisconnect;
    private static final double SCREEN_EDGE_PADDING = 4;

    public static boolean install(Stage stage, String iconResourcePath, String tooltip,
                                  Runnable connectAction, Runnable disconnectAction) {
        if (!SystemTray.isSupported()) {
            System.out.println("[Tray] 此系統不支援系統匣");
            return false;
        }
        onConnect = connectAction;
        onDisconnect = disconnectAction;
        Platform.setImplicitExit(false);

        try {
            Image image = Toolkit.getDefaultToolkit().getImage(
                    SystemTrayHelper.class.getResource(iconResourcePath));

            trayIcon = new TrayIcon(image, tooltip);
            trayIcon.setImageAutoSize(true);

            trayIcon.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e)  { maybePopup(e); }
                @Override public void mouseReleased(MouseEvent e) { maybePopup(e); }
                @Override public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                        showStage(stage);
                    }
                }
                private void maybePopup(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        int x = e.getXOnScreen();
                        int y = e.getYOnScreen();
                        Platform.runLater(() -> showMenu(stage, x, y));
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

    // ===== 自訂選單視窗（FX 執行緒）=====

    private static void showMenu(Stage mainStage, double screenX, double screenY) {
        if (menuStage != null && menuStage.isShowing()) {
            menuStage.hide();
        }

        VBox box = new VBox();
        box.getStyleClass().add("tray-menu");
        box.getChildren().addAll(
            row("ID: " + currentId, SystemTrayHelper::copyId),
            sep(),
            row("開啟拖曳板", () -> showStage(mainStage)),
            row("關閉拖曳板", mainStage::hide),
            sep(),
            row("建立連線", () -> { if (onConnect != null) onConnect.run(); }),
            row("斷開連線", () -> { if (onDisconnect != null) onDisconnect.run(); }),
            row("其他設定", () -> new SettingsStage().show()),
            sep(),
            row("結束", () -> System.exit(0))
        );

        menuStage = new Stage();
        menuStage.initStyle(StageStyle.TRANSPARENT);
        menuStage.setAlwaysOnTop(true);

        Scene scene = new Scene(box);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(
                SystemTrayHelper.class.getResource("/styles/app.css").toExternalForm());
        menuStage.setScene(scene);
        box.applyCss();
        box.layout();

        // 失焦自動關閉
        menuStage.focusedProperty().addListener((o, was, now) -> {
            if (!now && menuStage != null) {
                menuStage.hide();
            }
        });

        double menuWidth = Math.ceil(box.prefWidth(-1));
        double menuHeight = Math.ceil(box.prefHeight(menuWidth));
        placeMenuWithinScreen(screenX, screenY, menuWidth, menuHeight);
        menuStage.show();
        // 系統匣在右下角 → 選單往左上方開，並夾住不超出螢幕
        placeMenuWithinScreen(screenX, screenY, menuStage.getWidth(), menuStage.getHeight());
        menuStage.requestFocus();
    }

    private static void placeMenuWithinScreen(double anchorX, double anchorY, double menuWidth, double menuHeight) {
        Rectangle2D bounds = screenBoundsFor(anchorX, anchorY);
        double x = clamp(anchorX - menuWidth,
                bounds.getMinX() + SCREEN_EDGE_PADDING,
                bounds.getMaxX() - menuWidth - SCREEN_EDGE_PADDING);
        double y = clamp(anchorY - menuHeight,
                bounds.getMinY() + SCREEN_EDGE_PADDING,
                bounds.getMaxY() - menuHeight - SCREEN_EDGE_PADDING);
        menuStage.setX(x);
        menuStage.setY(y);
    }

    private static Rectangle2D screenBoundsFor(double screenX, double screenY) {
        return Screen.getScreensForRectangle(screenX, screenY, 1, 1).stream()
                .findFirst()
                .orElse(Screen.getPrimary())
                .getVisualBounds();
    }

    private static double clamp(double value, double min, double max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(value, max));
    }

    private static Label row(String text, Runnable action) {
        Label item = new Label(text);
        item.getStyleClass().add("tray-menu-item");
        item.setMaxWidth(Double.MAX_VALUE);
        item.setOnMouseClicked(e -> {
            if (menuStage != null) {
                menuStage.hide();   // 先關選單再執行動作
            }
            action.run();
        });
        return item;
    }

    private static Region sep() {
        Region r = new Region();
        r.getStyleClass().add("tray-menu-sep");
        r.setMinHeight(1);
        r.setPrefHeight(1);
        r.setMaxHeight(1);
        return r;
    }

    private static void copyId() {
        ClipboardContent content = new ClipboardContent();
        content.putString(currentId);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static void showStage(Stage stage) {
        Platform.runLater(() -> {
            stage.show();
            stage.setIconified(false);
            stage.toFront();
        });
    }
}
