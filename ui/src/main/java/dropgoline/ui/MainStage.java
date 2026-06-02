package dropgoline.ui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dropgoline.historyservice.HistoryManager;
import dropgoline.model.HistoryItem;
import dropgoline.net.P2PListener;
import dropgoline.net.P2PManager;
import dropgoline.settings.AppSettings;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public class MainStage extends Stage implements P2PListener {
    private final P2PManager p2p;
    private final FlowPane cardPane;
    private final Map<String, ModernCard> cards = new HashMap<>();
    private final Map<String, ProgressStage> activeProgress = new HashMap<>();
    private final Map<String, Timeline> progressSims = new HashMap<>();
    private final Map<String, File> pendingFiles = new HashMap<>();

    private File lastReceivedFile;
    private double dragOffsetX;
    private double dragOffsetY;
    private boolean trayInstalled = false;

    @SuppressWarnings("LeakingThisInConstructor")
    public MainStage(P2PManager p2p) {
        this.p2p = p2p;

        initStyle(StageStyle.TRANSPARENT);
        setTitle("DropGoLine");
        setAlwaysOnTop(true);
        setWidth(500);
        setHeight(400);

        loadIcon();

        trayInstalled = SystemTrayHelper.install(this, "/icons/app.png", "DropGoLine", this::handleConnect,
                p2p::disconnect);

        HBox topBar = buildTopBar();

        cardPane = new FlowPane();
        cardPane.setHgap(12);
        cardPane.setVgap(12);
        cardPane.setPadding(new Insets(15));

        SendCard sendCard = new SendCard(
                files -> files.forEach(p2p::broadcastFile),
                p2p::broadcastText);
        cardPane.getChildren().add(sendCard);

        ScrollPane scrollPane = new ScrollPane(cardPane);
        scrollPane.setFitToWidth(true);

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(scrollPane);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().addAll(
                getClass().getResource("/styles/app.css").toExternalForm(),
                getClass().getResource("/styles/modern-card.css").toExternalForm());
        root.getStyleClass().add("main-stage");
        setScene(scene);

        ResizeHelper.install(this);
        setOnShown(e -> WindowsAcrylic.apply(this));

        p2p.setListener(this);
    }

    private HBox buildTopBar() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minBtn = new Button("-");
        minBtn.getStyleClass().add("window-button");
        minBtn.setOnAction(e -> setIconified(true));

        Button closeBtn = new Button("x");
        closeBtn.getStyleClass().addAll("window-button", "close");
        closeBtn.setOnAction(e -> {
            if (trayInstalled) {
                hide();
            } else {
                Platform.exit();
            }
        });

        HBox bar = new HBox(spacer, minBtn, closeBtn);
        bar.getStyleClass().add("top-bar");
        bar.setMinHeight(28);
        bar.setPickOnBounds(true);
        bar.setOnMousePressed(e -> {
            dragOffsetX = e.getSceneX();
            dragOffsetY = e.getSceneY();
        });
        bar.setOnMouseDragged(e -> {
            setX(e.getScreenX() - dragOffsetX);
            setY(e.getScreenY() - dragOffsetY);
        });
        return bar;
    }

    private void loadIcon() {
        try (InputStream iconStream = getClass().getResourceAsStream("/icons/app.png")) {
            if (iconStream != null) {
                getIcons().add(new Image(iconStream));
            } else {
                System.out.println("[Icon] /icons/app.png not found");
            }
        } catch (IOException | RuntimeException ex) {
            System.out.println("[Icon] failed to load: " + ex.getMessage());
        }
    }

    private void handleConnect() {
        ConnectionStage conn = new ConnectionStage();
        conn.setOnConnect(code -> p2p.connect(code));
        conn.show();
    }

    private void openHistoryFor(String peerName) {
        List<HistoryItem> items = HistoryManager.getInstance().getHistory(peerName);
        System.out.println("[DEBUG] history items for " + peerName + ": " + items.size());
        new HistoryStage(peerName, items).show();
    }

    private void startDownload(String peerName) {
        System.out.println("[DropGoLine][UI] startDownload peer=" + peerName
                + ", alreadyActive=" + activeProgress.containsKey(peerName));
        if (activeProgress.containsKey(peerName)) {
            return;
        }

        ProgressStage progressStage = new ProgressStage(peerName + " download");
        activeProgress.put(peerName, progressStage);
        progressStage.show();
        progressStage.updateProgress(0);

        DoubleProperty p = new SimpleDoubleProperty(0);
        p.addListener((obs, ov, nv) -> progressStage.updateProgress(nv.doubleValue()));
        Timeline sim = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(p, 0)),
                new KeyFrame(Duration.seconds(10), new KeyValue(p, 0.9, Interpolator.EASE_IN)));
        progressSims.put(peerName, sim);
        sim.play();

        p2p.requestDownload(peerName);
    }

    private void copyToClipboard(String text) {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);
        System.out.println("[Clipboard] copied: " + text);
    }

    @Override
    public void onIdChanged(String id) {
        SystemTrayHelper.updateId(id);
        AppSettings s = AppSettings.current();
        s.setLastGroupCode(id);
        s.save();
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
            if (card == null) {
                return;
            }

            if (text.startsWith("OFFER_ID:")) {
                String[] parts = text.split(":", 3);
                if (parts.length >= 3) {
                    String fileId = parts[1];
                    String fileName = parts[2];
                    card.setPendingFile(fileName, 0);
                    card.setPendingFile(fileId);
                    card.setOnDownloadRequest(() -> p2p.sendText(peerName, "REQUEST_FILE:" + fileId));
                }
            } else if (text.startsWith("REQUEST_FILE:")) {
                String fileId = text.substring("REQUEST_FILE:".length());
                File file = pendingFiles.get(fileId);
                if (file != null) {
                    p2p.sendFile(peerName, file);
                    p2p.sendText(peerName, "FILE_ARRIVED:" + fileId + "|" + file.getName());
                }
            } else if (text.startsWith("FILE_ARRIVED:")) {
                String content = text.substring("FILE_ARRIVED:".length());
                String[] parts = content.split("\\|", 2);
                String fileName = parts.length == 2 ? parts[1] : content;
                File receivedFile = lastReceivedFile;
                if (receivedFile != null) {
                    card.setFile(receivedFile);
                    card.setDownloaded(true);
                    card.setText("Received file: " + fileName);
                } else {
                    card.setText("File received: " + fileName);
                }
            } else {
                System.out.println("[DropGoLine][UI] message from=" + peerName + ": " + text);
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
            Timeline sim = progressSims.remove(peerName);
            if (sim != null) {
                sim.stop();
            }

            ProgressStage ps = activeProgress.remove(peerName);
            if (ps != null) {
                ps.updateProgress(1);
                ps.close();
            }

            ModernCard card = cards.get(peerName);
            if (card != null) {
                card.setFile(file);
                card.setDownloaded(true);
            }

            System.out.println("Transfer complete: " + file.getName());
        });
    }

    private void addPeer(String name) {
        if (cards.containsKey(name)) {
            return;
        }

        ModernCard card = new ModernCard(name);
        card.setText("Ready");
        card.setOnHistoryRequest(() -> openHistoryFor(name));
        card.setOnDownloadRequest(() -> startDownload(name));
        card.setOnTextDropped(text -> {
            p2p.sendText(name, text);
            card.setText("Sent text");
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                    Platform.runLater(() -> card.setText("Ready"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "text-drop-reset").start();
        });
        card.setOnFileDropped(file -> {
            String fileId = java.util.UUID.randomUUID().toString();
            pendingFiles.put(fileId, file);
            p2p.sendText(name, "OFFER_ID:" + fileId + ":" + file.getName());
            System.out.println("[DropGoLine][UI] Calling p2p.sendFile peer=" + name
                    + ", file=" + file.getAbsolutePath() + ", size=" + file.length());
            p2p.sendFile(name, file);
        });

        cards.put(name, card);
        cardPane.getChildren().add(card);
    }

    private void removePeer(String name) {
        ModernCard card = cards.remove(name);
        if (card != null) {
            cardPane.getChildren().remove(card);
        }

        Timeline sim = progressSims.remove(name);
        if (sim != null) {
            sim.stop();
        }

        ProgressStage ps = activeProgress.remove(name);
        if (ps != null) {
            ps.close();
        }
    }
}
