package dropgoline.ui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dropgoline.historyservice.HistoryManager;
import dropgoline.net.P2PListener;
import dropgoline.net.P2PManager;
import dropgoline.settings.AppSettings;
import dropgoline.util.PeerIds;
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
    private static final long MAX_REMOTE_IMAGE_BYTES = 20L * 1024 * 1024;

    private final P2PManager p2p;
    private final FlowPane cardPane;
    private final Map<String, ModernCard> cards = new HashMap<>();
    private final Map<String, ProgressStage> activeProgress = new HashMap<>();
    private final Map<String, Timeline> progressSims = new HashMap<>();

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
                files -> files.forEach(this::broadcastFileWithHistory),
                this::broadcastTextWithHistory,
                this::importImageSourceAndSend);
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
        HistoryStage history = new HistoryStage(peerName, HistoryManager.getInstance().getHistory(peerName));
        history.show();
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

    private void broadcastTextWithHistory(String text) {
        p2p.broadcastText(text);
        for (String peerName : cards.keySet()) {
            HistoryManager.getInstance().addHistory(peerName, text, false, "TEXT");
        }
    }

    private void broadcastFileWithHistory(File file) {
        p2p.broadcastFile(file);
        addOutgoingFileHistory(file);
    }

    private void addOutgoingFileHistory(File file) {
        String type = isImageFile(file) ? "IMAGE" : "FILE";
        String content = file.getAbsolutePath();
        for (String peerName : cards.keySet()) {
            HistoryManager.getInstance().addHistory(peerName, content, false, type);
        }
    }

    private void importImageSourceAndSend(String imageSource) {
        CompletableFuture.runAsync(() -> {
            try {
                File file = imageSource.trim().toLowerCase(Locale.ROOT).startsWith("data:image/")
                        ? writeDataUrlImage(imageSource)
                        : downloadRemoteImage(imageSource);
                broadcastFileWithHistory(file);
            } catch (Exception ex) {
                System.err.println("[Image] import failed from " + shortImageSource(imageSource)
                        + ": " + shortErrorMessage(ex));
            }
        });
    }

    private File writeDataUrlImage(String dataUrl) throws IOException {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            throw new IOException("invalid data URL");
        }

        String header = dataUrl.substring(0, comma).toLowerCase(Locale.ROOT);
        String base64 = dataUrl.substring(comma + 1);
        if (!header.startsWith("data:image/") || !header.contains(";base64")) {
            throw new IOException("unsupported data URL image format");
        }

        String contentType = header.substring("data:".length());
        int semicolon = contentType.indexOf(';');
        if (semicolon >= 0) {
            contentType = contentType.substring(0, semicolon);
        }
        if (!isImageContentType(contentType)) {
            throw new IOException("data URL is not an image: " + contentType);
        }

        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            throw new IOException("invalid image data", ex);
        }
        if (bytes.length > MAX_REMOTE_IMAGE_BYTES) {
            throw new IOException("image is larger than " + MAX_REMOTE_IMAGE_BYTES + " bytes");
        }

        Path target = Files.createTempFile("dropgoline-image-", extensionForContentType(contentType));
        Files.write(target, bytes);
        return target.toFile();
    }

    private File downloadRemoteImage(String imageUrl) throws Exception {
        URI uri = URI.create(imageUrl.trim());
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("unsupported image URL: " + imageUrl);
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(20))
                .header("User-Agent", "DropGoLine/0.1")
                .GET()
                .build();
        HttpResponse<InputStream> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " for " + imageUrl);
        }

        String contentType = response.headers()
                .firstValue("Content-Type")
                .orElse("");
        if (!isImageContentType(contentType)) {
            throw new IOException("URL is not an image: " + contentType);
        }

        Path target = Files.createTempFile("dropgoline-image-", extensionForContentType(contentType));
        long written = 0;
        byte[] buffer = new byte[8192];
        try (InputStream in = response.body();
                var out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                written += read;
                if (written > MAX_REMOTE_IMAGE_BYTES) {
                    throw new IOException("image is larger than " + MAX_REMOTE_IMAGE_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
            }
        }
        return target.toFile();
    }

    private boolean isImageContentType(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private String extensionForContentType(String contentType) {
        String normalized = contentType == null
                ? ""
                : contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return switch (normalized) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/bmp" -> ".bmp";
            case "image/webp" -> ".webp";
            default -> ".img";
        };
    }

    private String shortImageSource(String source) {
        if (source == null) {
            return "(empty)";
        }
        String text = source.trim();
        if (text.toLowerCase(Locale.ROOT).startsWith("data:image/")) {
            int comma = text.indexOf(',');
            String header = comma >= 0 ? text.substring(0, comma) : text;
            return header + ",...";
        }
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    private String shortErrorMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) + "..." : message;
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
            if (card != null) {
                System.out.println("[DropGoLine][UI] message from=" + peerName + ": " + text);
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
                if (isImageFileName(fileName)) {
                    startDownload(peerName);
                }
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
                if (isImageFile(file)) {
                    Image image = new Image(file.toURI().toString(), 180, 100, true, true);
                    card.setPreviewImage(image);
                }
                card.setDownloaded(true);
            }

            System.out.println("Transfer complete: " + file.getName());
        });
    }

    private boolean isImageFile(File file) {
        return file != null && isImageFileName(file.getName());
    }

    private boolean isImageFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".gif")
                || name.endsWith(".bmp")
                || name.endsWith(".webp");
    }

    private void addPeer(String name) {
        if (cards.containsKey(name)) {
            return;
        }

        ModernCard card = new ModernCard(name, PeerIds.displayName(name));
        card.setText("Ready");
        card.setClearAfterDrag(true);
        card.setOnHistoryRequest(() -> openHistoryFor(name));
        card.setOnDownloadRequest(() -> startDownload(name));
        card.setOnTextDropped(text -> {
            p2p.sendText(name, text);
            HistoryManager.getInstance().addHistory(name, text, false, "TEXT");
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
            System.out.println("[DropGoLine][UI] Calling p2p.sendFile peer=" + name
                    + ", file=" + file.getAbsolutePath() + ", size=" + file.length());
            p2p.sendFile(name, file);
            HistoryManager.getInstance().addHistory(name, file.getAbsolutePath(), false,
                    isImageFile(file) ? "IMAGE" : "FILE");
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
