package dropgoline.ui;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dropgoline.net.P2PListener;
import dropgoline.net.P2PManager;
import dropgoline.settings.AppSettings;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class MainStage extends Stage implements P2PListener {
    private final P2PManager p2p;
    private final FlowPane cardPane;
    private final Map<String, ModernCard> cards = new HashMap<>();
    private final Map<String, ProgressStage> activeProgress = new HashMap<>();
    private final Label idLabel;

    private double dragOffsetX;
    private double dragOffsetY;

    public MainStage(P2PManager p2p) {
        this.p2p = p2p;

        initStyle(StageStyle.UNDECORATED);

        setTitle("DropGoLine");
        setWidth(500);
        setHeight(400);

        loadIcon();

        HBox titleBar = buildTitleBar();

        MenuBar menuBar = buildMenuBar();

        VBox topArea = new VBox(titleBar, menuBar);

        cardPane = new FlowPane();
        cardPane.setHgap(12);
        cardPane.setVgap(12);
        cardPane.setPadding(new Insets(15));

        ScrollPane scrollPane = new ScrollPane(cardPane);
        scrollPane.setFitToWidth(true);

        idLabel = new Label("ID: -");
        idLabel.getStyleClass().add("id-label");
        idLabel.setPadding(new Insets(4, 10, 4, 10));
        idLabel.setMaxWidth(Double.MAX_VALUE);

        BorderPane root = new BorderPane();
        root.setTop(topArea);
        root.setCenter(scrollPane);
        root.setBottom(idLabel);

        Scene scene = new Scene(root);
        scene.getStylesheets().addAll(
            getClass().getResource("/styles/app.css").toExternalForm(),
            getClass().getResource("/styles/modern-card.css").toExternalForm()
        );
        setScene(scene);

        p2p.setListener(this);
    }

    private HBox buildTitleBar() {
        Label titleLabel = new Label("DropGoLine");
        titleLabel.getStyleClass().add("title-text");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().add("window-button");
        closeBtn.setOnAction(e -> close());

        Button minBtn = new Button("-");
        minBtn.getStyleClass().add("window-button");
        minBtn.setOnAction(e -> setIconified(true));

        HBox titleBar = new HBox(titleLabel, spacer, minBtn, closeBtn);
        titleBar.getStyleClass().add("title-bar");
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setMinHeight(30);

        titleBar.setOnMousePressed(e -> {
            dragOffsetX = e.getSceneX();
            dragOffsetY = e.getSceneY();
        });
        titleBar.setOnMouseDragged(e -> {
            setX(e.getScreenX() - dragOffsetX);
            setY(e.getScreenY() - dragOffsetY);
        });

        return titleBar;
    }

    private void loadIcon() {
        try (InputStream iconStream = getClass().getResourceAsStream("/icons/app.png")) {
            if (iconStream != null) {
                getIcons().add(new Image(iconStream));
            } else {
                System.out.println("[Icon] 找不到 /icons/app.png，使用預設圖示");
            }
        } catch (Exception ex) {
            System.out.println("[Icon] 載入失敗：" + ex.getMessage());
        }
    }

    private MenuBar buildMenuBar() {
        MenuItem connectItem = new MenuItem("建立連線");
        connectItem.setOnAction(e -> handleConnect());

        MenuItem disconnectItem = new MenuItem("斷開連線");
        disconnectItem.setOnAction(e -> p2p.disconnect());

        MenuItem settingsItem = new MenuItem("其他設定");
        settingsItem.setOnAction(e -> new SettingsStage().show());

        Menu optionsMenu = new Menu("選項");
        optionsMenu.getItems().addAll(connectItem, disconnectItem, new SeparatorMenuItem(), settingsItem);

        return new MenuBar(optionsMenu);
    }

    private void handleConnect() {
        ConnectionStage conn = new ConnectionStage();
        conn.setOnConnect(code -> p2p.connect(code));
        conn.show();
    }

    private void openHistoryFor(String peerName) {
        HistoryStage history = new HistoryStage(peerName, List.of());
        history.show();
    }

    private void startDownload(String peerName) {
        if (activeProgress.containsKey(peerName)) {
            return;
        }
        ProgressStage progressStage = new ProgressStage(peerName + " 的檔案");
        activeProgress.put(peerName, progressStage);
        progressStage.show();
        p2p.requestDownload(peerName);
    }
    
    private void copyToClipboard(String text) {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);
        System.out.println("[Clipboard] 已寫入：" + text);
    }

    @Override
    public void onIdChanged(String id) {
        Platform.runLater(() -> idLabel.setText("ID: " + id));
    }

    @Override
    public void onPeerJoined(String peerName) {
        Platform.runLater(() -> addPeer(peerName));
    }

    @Override
    public void onPeerLeft(String peerName) {
        Platform.runLater(() -> removePeer(peerName));
    }

    @Override
    public void onMessageReceived(String peerName, String text) {
        Platform.runLater(() -> {
            ModernCard card = cards.get(peerName);
            if (card != null) {
                card.setText(text);
            }

            if (AppSettings.current().isAutoClipboardCopy()) {
                copyToClipboard(text);
            }
        });
    }


    @Override
    public void onFileOffer(String peerName, String fileName, long fileSize) {
        Platform.runLater(() -> {
            ModernCard card = cards.get(peerName);
            if (card != null) {
                card.setPendingFile(fileName, fileSize);
            }
        });
    }

    @Override
    public void onTransferProgress(String peerName, double progress) {
        Platform.runLater(() -> {
            ModernCard card = cards.get(peerName);
            if (card != null) {
                card.setProgress(progress);
            }
        });
    }

    @Override
    public void onTransferComplete(String peerName, File file) {
        Platform.runLater(() -> {
            ProgressStage ps = activeProgress.remove(peerName);
            if (ps != null) {
                ps.close();
            }

            ModernCard card = cards.get(peerName);
            if (card != null) {
                card.setFile(file);
                card.setDownloaded(true);
            }
        });
    }

    private void addPeer(String name) {
        if (cards.containsKey(name)) {
            return;
        }
        ModernCard card = new ModernCard(name);
        card.setText("尚無訊息");
        card.setOnHistoryRequest(() -> openHistoryFor(name));
        card.setOnDownloadRequest(() -> startDownload(name));

        cards.put(name, card);
        cardPane.getChildren().add(card);
    }

    private void removePeer(String name) {
        ModernCard card = cards.remove(name);
        if (card != null) {
            cardPane.getChildren().remove(card);
        }

        ProgressStage ps = activeProgress.remove(name);
        if (ps != null) {
            ps.close();
        }
    }
}
