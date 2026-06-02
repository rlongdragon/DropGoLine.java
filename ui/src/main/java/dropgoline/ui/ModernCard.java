package dropgoline.ui;

import java.io.File;
import java.util.List;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.util.Duration;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;

public class ModernCard extends StackPane {
    private final String peername;
    private final Label nameLabel;
    private final Label contentLabel;
    private final Label checkMark;
    private final BorderPane layout;
    private final ImageView previewView;
    private final ProgressBar transferBar;

    private String dragText = null;
    private File dragFile = null;

    private MenuItem downloadItem;
    private Runnable onDownloadRequest;
    private Runnable onHistoryRequest;
    private boolean clearAfterDrag = false;

    public ModernCard(String name) {
        this.peername = name;
        getStyleClass().add("modern-card");

        nameLabel = new Label(name);
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

        checkMark = new Label("✓");
        checkMark.getStyleClass().add("card-check");
        checkMark.setVisible(false);
        StackPane.setAlignment(checkMark, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(checkMark, new Insets(0, 8, 8, 0));

        getChildren().addAll(layout, checkMark);

        setPrefSize(200, 150);

        setupDragSource();
        setupContextMenu();
        setupHoverAnimation();
    }


    private void setupDragSource() {
        setOnDragDetected(event -> {
            ClipboardContent content = new ClipboardContent();
            if (dragFile != null) {
                content.putFiles(List.of(dragFile));
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
            if (clearAfterDrag && event.getTransferMode() != null) {
                clearContent();
            }
            event.consume();
        });
    }

    private void setupContextMenu() {
        ContextMenu menu = new ContextMenu();

        downloadItem = new MenuItem("下載");
        downloadItem.setVisible(false);
        downloadItem.setOnAction(e -> {
            if (onDownloadRequest != null) {
                onDownloadRequest.run();
            }
        });

        MenuItem historyItem = new MenuItem("歷史紀錄");
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

    private void setupHoverAnimation(){
        setOnMouseEntered(e -> animateScale(1.03));
        setOnMouseExited(e -> animateScale(1.0));
    }

    private void animateScale(double target){
        Timeline t = new Timeline(
            new KeyFrame(Duration.millis(150),
                new KeyValue(scaleXProperty(), target, Interpolator.EASE_OUT),
                new KeyValue(scaleYProperty(), target, Interpolator.EASE_OUT)
            )
        );
        t.play();
    }

    public void setPendingFile(String fileName, long fileSize){
        contentLabel.setText("📥 " + fileName + "\n" + formatSize(fileSize));
        layout.setCenter(contentLabel);
        dragText = null;
        dragFile = null;
        downloadItem.setVisible(true);
    }

    public void setText(String text) {
        contentLabel.setText(text);
        layout.setCenter(contentLabel);
        dragText = text;
        dragFile = null;
        if (downloadItem != null){
            downloadItem.setVisible(false);
        }
    }

    public void setFile(File file) {
        contentLabel.setText("📄 " + file.getName());
        layout.setCenter(contentLabel);
        dragFile = file;
        dragText = null;
        if (downloadItem != null){
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
        if (progress <= 0 || progress > 1.0) {
            transferBar.setVisible(false);
            transferBar.setManaged(false);
        } else {
            transferBar.setVisible(true);
            transferBar.setManaged(true);
            transferBar.setProgress(progress);
        }
    }

    public void setDownloaded(boolean downloaded) {
        checkMark.setVisible(downloaded);
    }

    public void setClearAfterDrag(boolean clearAfterDrag) {
        this.clearAfterDrag = clearAfterDrag;
    }

    public void clearContent() {
        contentLabel.setText("");
        layout.setCenter(contentLabel);
        previewView.setImage(null);
        dragText = null;
        dragFile = null;
        setProgress(0);
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

    public String getPeerName() {
        return peername;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
