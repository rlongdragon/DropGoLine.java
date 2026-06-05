package dropgoline.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.swing.Icon;
import javax.swing.filechooser.FileSystemView;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

public class ModernCard extends StackPane {
    private static final int FILE_ICON_SIZE = 32;
    private static final Map<String, Image> FILE_ICON_CACHE = new ConcurrentHashMap<>();

    private final String peername;
    private final Label nameLabel;
    private final Label contentLabel;
    private final Label checkMark;
    private final BorderPane layout;
    private final ImageView previewView;
    private final ProgressBar transferBar;

    private String dragText = null;
    private File dragFile = null;
    private String pendingOfferId;

    private MenuItem downloadItem;
    private Runnable onDownloadRequest;
    private Runnable onHistoryRequest;
    private Consumer<File> onFileDropped;
    private Consumer<String> onTextDropped;
    private boolean hasPendingDownload = false;

    public ModernCard(String name) {
        this(name, name);
    }

    public ModernCard(String name, String displayName) {
        this.peername = name;
        getStyleClass().add("modern-card");

        nameLabel = new Label(displayName);
        nameLabel.getStyleClass().add("card-name");

        contentLabel = new Label("");
        contentLabel.getStyleClass().add("card-content");
        contentLabel.setWrapText(true);

        previewView = new ImageView();
        previewView.fitWidthProperty().bind(widthProperty().subtract(24));
        previewView.fitHeightProperty().bind(heightProperty().subtract(48));
        previewView.setPreserveRatio(true);
        previewView.setSmooth(true);

        transferBar = new ProgressBar(0);
        transferBar.getStyleClass().add("card-progress");
        transferBar.setMaxWidth(Double.MAX_VALUE);
        transferBar.setVisible(false);
        transferBar.setManaged(false);

        layout = new BorderPane();
        BorderPane.setMargin(nameLabel, new Insets(0, 0, 0, 4));
        layout.setTop(nameLabel);
        layout.setCenter(contentLabel);
        layout.setBottom(transferBar);

        checkMark = new Label("OK");
        checkMark.getStyleClass().add("card-check");
        checkMark.setVisible(false);
        StackPane.setAlignment(checkMark, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(checkMark, new Insets(0, 8, 8, 0));

        getChildren().addAll(layout, checkMark);

        setPrefSize(200, 150);

        setupDragReceiving();
        setupDragSource();
        setupContextMenu();
        setupClickActions();
        setupHoverAnimation();
    }

    private void setupDragReceiving() {
        setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (event.getGestureSource() != this && (db.hasFiles() || db.hasString())) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
                getStyleClass().add("drag-over");
            }
            event.consume();
        });

        setOnDragExited(event -> {
            getStyleClass().remove("drag-over");
            event.consume();
        });

        setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;

            if (db.hasFiles()) {
                File file = db.getFiles().get(0);
                System.out.println("[DropGoLine][UI] File dropped on card peer=" + peername
                        + ", file=" + file.getAbsolutePath() + ", size=" + file.length());
                setFile(file);
                if (onFileDropped != null) {
                    onFileDropped.accept(file);
                }
                success = true;
            } else if (db.hasString()) {
                String text = db.getString();
                setText(text);
                if (onTextDropped != null) {
                    onTextDropped.accept(text);
                }
                success = true;
            }

            event.setDropCompleted(success);
            getStyleClass().remove("drag-over");
            event.consume();
        });
    }

    private void setupDragSource() {
        setOnDragDetected(event -> {
            ClipboardContent content = new ClipboardContent();
            if (dragFile != null && dragFile.exists()) {
                content.putFiles(Collections.singletonList(dragFile));
            } else if (dragText != null && !dragText.isEmpty()) {
                content.putString(dragText);
            } else {
                event.consume();
                return;
            }
            Dragboard db = startDragAndDrop(TransferMode.COPY);
            db.setContent(content);
            event.consume();
        });

        setOnDragDone(event -> {
            event.consume();
        });
    }

    private void setupContextMenu() {
        ContextMenu menu = new ContextMenu();

        downloadItem = new MenuItem("Download");
        downloadItem.setVisible(false);
        downloadItem.setOnAction(e -> {
            if (onDownloadRequest != null) {
                onDownloadRequest.run();
            }
        });

        MenuItem historyItem = new MenuItem("History");
        historyItem.setOnAction(e -> {
            if (onHistoryRequest != null) {
                onHistoryRequest.run();
            }
        });
        menu.getItems().addAll(downloadItem, historyItem);

        setOnContextMenuRequested(event -> {
            menu.show(this, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    private void setupClickActions() {
        setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 1) {
                return;
            }
            if (!hasPendingDownload) {
                return;
            }
            if (onDownloadRequest != null) {
                onDownloadRequest.run();
            }
            event.consume();
        });
    }

    private void setupHoverAnimation() {
        setOnMouseEntered(e -> animateScale(1.03));
        setOnMouseExited(e -> animateScale(1.0));
    }

    private void animateScale(double target) {
        Timeline t = new Timeline(
            new KeyFrame(Duration.millis(150),
                new KeyValue(scaleXProperty(), target, Interpolator.EASE_OUT),
                new KeyValue(scaleYProperty(), target, Interpolator.EASE_OUT)
            )
        );
        t.play();
    }

    public void setPendingFile(String offerId) {
        this.pendingOfferId = offerId;
    }

    public String getPendingOfferId() {
        return pendingOfferId;
    }

    public void setPendingFile(String fileName, long fileSize) {
        layout.setCenter(createFileContent(fileName, formatSize(fileSize), new File(fileName)));
        dragText = null;
        dragFile = null;
        hasPendingDownload = true;
        setDownloaded(false);
        downloadItem.setVisible(true);
    }

    public void setText(String text) {
        contentLabel.setText(text);
        layout.setCenter(contentLabel);
        previewView.setImage(null);
        dragText = text;
        dragFile = null;
        hasPendingDownload = false;
        clearProgress();
        if (downloadItem != null) {
            downloadItem.setVisible(false);
        }
    }

    public void setFile(File file) {
        String detail = file.exists() ? formatSize(file.length()) : "File";
        layout.setCenter(createFileContent(file.getName(), detail, file));
        previewView.setImage(null);
        dragFile = file;
        dragText = null;
        hasPendingDownload = false;
        if (downloadItem != null) {
            downloadItem.setVisible(false);
        }
    }

    public void setPreviewImage(Image image) {
        if (image == null) {
            previewView.setImage(null);
            layout.setCenter(contentLabel);
        } else {
            previewView.setImage(image);
            layout.setCenter(previewView);
        }
    }

    public void setProgress(double progress) {
        if (progress < 0 || progress > 1.0) {
            clearProgress();
            return;
        }
        transferBar.setVisible(true);
        transferBar.setManaged(true);
        transferBar.setProgress(progress);
    }

    public void clearProgress() {
        transferBar.setProgress(0);
        transferBar.setVisible(false);
        transferBar.setManaged(false);
    }

    public void setDownloaded(boolean downloaded) {
        checkMark.setVisible(downloaded);
    }

    public void clearContent() {
        contentLabel.setText("");
        layout.setCenter(contentLabel);
        previewView.setImage(null);
        dragText = null;
        dragFile = null;
        hasPendingDownload = false;
        pendingOfferId = null;
        clearProgress();
        setDownloaded(false);
        if (downloadItem != null) {
            downloadItem.setVisible(false);
        }
    }

    public void setOnHistoryRequest(Runnable handler) {
        this.onHistoryRequest = handler;
    }

    public void setOnDownloadRequest(Runnable handler) {
        this.onDownloadRequest = handler;
    }

    public void setOnFileDropped(Consumer<File> handler) {
        this.onFileDropped = handler;
    }

    public void setOnTextDropped(Consumer<String> handler) {
        this.onTextDropped = handler;
    }

    public String getPeerName() {
        return peername;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private Node createFileContent(String fileName, String detail, File iconFile) {
        Label fileNameLabel = new Label(fileName);
        fileNameLabel.getStyleClass().add("card-file-name");
        fileNameLabel.setWrapText(true);

        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("card-file-detail");

        VBox textBox = new VBox(2, fileNameLabel, detailLabel);
        textBox.setAlignment(Pos.CENTER_LEFT);

        Image icon = fileIconFor(iconFile);
        if (icon == null) {
            contentLabel.setText(fileName + "\n" + detail);
            return contentLabel;
        }

        ImageView iconView = new ImageView(icon);
        iconView.setFitWidth(FILE_ICON_SIZE);
        iconView.setFitHeight(FILE_ICON_SIZE);
        iconView.setPreserveRatio(true);
        iconView.setSmooth(true);

        HBox row = new HBox(10, iconView, textBox);
        row.getStyleClass().add("card-file-content");
        row.setAlignment(Pos.CENTER);
        return row;
    }

    private Image fileIconFor(File file) {
        String cacheKey = iconCacheKey(file);
        return FILE_ICON_CACHE.computeIfAbsent(cacheKey, key -> loadSystemIcon(file));
    }

    private String iconCacheKey(File file) {
        if (file == null) {
            return "";
        }
        if (file.isDirectory()) {
            return "<directory>";
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "<file>";
    }

    private Image loadSystemIcon(File file) {
        try {
            Icon icon = FileSystemView.getFileSystemView().getSystemIcon(file, FILE_ICON_SIZE, FILE_ICON_SIZE);
            return toFxImage(icon);
        } catch (RuntimeException | Error ex) {
            return null;
        }
    }

    private Image toFxImage(Icon icon) {
        int width = Math.max(1, icon.getIconWidth());
        int height = Math.max(1, icon.getIconHeight());
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = buffered.createGraphics();
        icon.paintIcon(null, graphics, 0, 0);
        graphics.dispose();

        WritableImage image = new WritableImage(width, height);
        PixelWriter writer = image.getPixelWriter();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                writer.setArgb(x, y, buffered.getRGB(x, y));
            }
        }
        return image;
    }
}
