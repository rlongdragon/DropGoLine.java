package dropgoline.ui;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import p2p.transfer.FileTransferService;
import p2p.transfer.FileTransferProtocol;
import p2p.transfer.ChecksumService;
import p2p.quic.QuicChannel;
import java.nio.file.Path;

import dropgoline.net.P2PListener;
import dropgoline.net.P2PManager;
import dropgoline.settings.AppSettings;
import dropgoline.model.HistoryItem;
import dropgoline.historyservice.HistoryManager;

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
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class MainStage extends Stage implements P2PListener {
    private final P2PManager p2p;
    private final FlowPane cardPane;
    private final Map<String, ModernCard> cards = new HashMap<>();
    private final Map<String, ProgressStage> activeProgress = new HashMap<>();
    private final Label idLabel;
    private final FileTransferService transferService;
    private File lastReceivedFile;
    

    private final Map<String,File> pendingFiles = new HashMap<>();
    private final Map<String, File> receivedFilesById = new HashMap<>(); 
    private double dragOffsetX;
    private double dragOffsetY;
    private boolean trayInstalled = false;
    

    public MainStage(P2PManager p2p) {
        this.p2p = p2p;

        ChecksumService checksumService = new ChecksumService();
        FileTransferProtocol protocol = new FileTransferProtocol(checksumService);
        this.transferService = new FileTransferService(protocol);

        initStyle(StageStyle.TRANSPARENT);

        setTitle("DropGoLine");
        setWidth(500);
        setHeight(400);

        loadIcon();

        trayInstalled = SystemTrayHelper.install(this, "/icons/app.png", "DropGoLine");

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
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().addAll(
                getClass().getResource("/styles/app.css").toExternalForm(),
                getClass().getResource("/styles/modern-card.css").toExternalForm());
        setScene(scene);

        setOnShown(e -> WindowsAcrylic.apply(this));

        p2p.setListener(this);
    }

    private HBox buildTitleBar() {
        Label titleLabel = new Label("DropGoLine");
        titleLabel.getStyleClass().add("title-text");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().addAll("window-button", "close");
        closeBtn.setOnAction(e -> {
            if (trayInstalled) {
                hide();
            } else {
                Platform.exit();
            }
        });
    

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
        List<HistoryItem> items = HistoryManager.getInstance().getHistory(peerName);
        System.out.println("[DEBUG] 讀取的歷史紀錄筆數: " + items.size());
        HistoryStage history = new HistoryStage(peerName, items);
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
            if (card == null) return;

            if (text.startsWith("OFFER_ID:")) {
                String[] parts = text.split(":");
                String fileid = parts[1];
                String fileName = parts[2];
                card.setPendingFile(fileName,0);
                card.setPendingFile(fileid);
                
                card.setOnDownloadRequest(() -> {
                    p2p.sendText(peerName, "REQUEST_FILE:" + fileid);
                });
                
            } else if (text.startsWith("REQUEST_FILE:")) {
                String fileId = text.split(":")[1];
               
                File file = pendingFiles.get(fileId);

                if (file != null) {
                    try {
                        System.out.println("[DEBUG] 開始調用 p2p.sendFile");
                        p2p.sendFile(peerName, file);
                        p2p.sendText(peerName, "FILE_ARRIVED:" + fileId + "|" + file.getName());
                        System.out.println("[DEBUG] p2p.sendFile 已呼叫完畢");
                    } catch (Exception e) {
                        e.printStackTrace(); // 看看這裡有沒有印出錯誤訊息！
                    }
                }
            } else if (text.startsWith("FILE_ARRIVED:")) {
                String content = text.substring("FILE_ARRIVED:".length());
                String parts[] = content.split("\\|",2);
                if (parts.length == 2) {
                    String fileId = parts[0];
                    String fileName = parts[1];
                    File receivedFile = lastReceivedFile;
                    Platform.runLater(() ->{
                        if (receivedFile != null) {
                            card.setFile(receivedFile);
                            card.setDownloaded(true);
                            card.setText("已接收檔案: " + fileName);
                        } else {
                            card.setText("接收檔案完成 (但找不到檔案): " + fileName);
                        }
                    });
                }    
            // 這裡就是觸發 UI 更新的地方
            //card.setText("已接收檔案: " + fileName);
            } else {
                System.out.println("[DEBUG] 錯誤：找不到對應的檔案！ID 是否過期？");
                System.out.println(text);
                card.setText(text);
                if (AppSettings.current().isAutoClipboardCopy()) {
                    copyToClipboard(text);
                }
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
        this.lastReceivedFile = file;
        Platform.runLater(() -> {
        ModernCard card = cards.get(peerName);
        if (card != null) {
            card.setFile(file); 
            card.setDownloaded(true);
            receivedFilesById.put(file.getName(),file);

            System.out.println("傳輸完成:" + file.getName());
        }
    });
    }

    private void addPeer(String name) {
        if (cards.containsKey(name)) {
            return;
        }

        ModernCard card = new ModernCard(name);

        card.setOnFileDropped(file ->{
            String fileId = java.util.UUID.randomUUID().toString();
            pendingFiles.put(fileId,file);

            p2p.sendText(name, "OFFER_ID:" + fileId + ":" + file.getName());

        });

        card.setOnTextDropped((text) -> {
            // 1. 傳送文字
            p2p.sendText(name, text);
            // 2. 直接顯示在 UI 上
            card.setText("已傳送文字"); 


            new Thread(() -> {
                try {
                    Thread.sleep(500); 
                    Platform.runLater(() -> card.setText("尚無訊息"));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }).start();

        });

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
