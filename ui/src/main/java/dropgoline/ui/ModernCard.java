package dropgoline.ui;

import java.io.File;
import java.security.Key;
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
import javafx.scene.control.TextField;
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
    private String pendingOfferId;

    private MenuItem downloadItem;
    private Runnable onDownloadRequest;
    private Runnable onHistoryRequest;
    private java.util.function.Consumer<File> onFileDropped;

    public void setOnFileDropped(java.util.function.Consumer<File> handler) {
        this.onFileDropped = handler;
    }
    

    public void setPendingFile(String Id) {
        this.pendingOfferId = Id;
    }

    public String getPendingOfferId() { return pendingOfferId; }


    public ModernCard(String name) {
        this.peername = name;
        getStyleClass().add("modern-card");

        nameLabel = new Label(name);
        nameLabel.getStyleClass().add("card-name");

        contentLabel = new Label("");
        contentLabel.getStyleClass().add("card-content");
        contentLabel.setWrapText(true);

        previewView = new ImageView();
        previewView.setFitWidth(180);
        previewView.setFitHeight(100);
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

        setupDragReceiving();
        setupDragSource();
        setupContextMenu();
        setupHoverAnimation();
    }

    private void setupDragReceiving() {
        setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasFiles() || db.hasString()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        setOnDragEntered(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasFiles() || db.hasString()) {
                getStyleClass().add("drag-over");
            }
            event.consume();
        });

        setOnDragOver(event -> {
            if (event.getGestureSource() != this && (event.getDragboard().hasFiles() || event.getDragboard().hasString())) {
            event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
        }
            getStyleClass().add("drag-over"); // 如果你有定義 CSS 樣式
            event.consume();
        });

        setOnDragExited(event -> {
            getStyleClass().remove("drag-over");
            event.consume();
        });

        // setOnDragDropped(event -> {
        //     Dragboard db = event.getDragboard();
        //     if (db.hasFiles()) {
        //         File file = db.getFiles().get(0);
                
        //         if (onFileDropped != null) {
        //             onFileDropped.accept(file);
        //         }
                
        //         event.setDropCompleted(true);
        //     }
        //     getStyleClass().remove("drag-over");
        //     event.consume();
        // });

        setOnDragDropped(event -> {
        Dragboard db = event.getDragboard();
        boolean success = false;

        if (db.hasFiles()) {
            File file = db.getFiles().get(0);
            if (onFileDropped != null) {
                onFileDropped.accept(file);
                success = true;
            }
        } 
        // 【新增】：處理文字拖曳
        else if (db.hasString()) {
            String text = db.getString();
            if (onTextDropped != null) {
                onTextDropped.accept(text);
                success = true;
            }
        }

        event.setDropCompleted(success);
        getStyleClass().remove("drag-over");
        event.consume();
    });
        

    }

    private java.util.function.Consumer<String> onTextDropped;

    public void setOnTextDropped(java.util.function.Consumer<String> handler) {
        this.onTextDropped = handler;
    }

    private void setupDragSource() {
        setOnDragDetected(event -> {
            if ((dragFile != null && dragFile.exists()) || (dragText != null && !dragText.isEmpty())) {
                Dragboard db = this.startDragAndDrop(TransferMode.COPY);
                ClipboardContent content = new ClipboardContent();

                // 如果有檔案，放檔案；如果有文字，放文字
                if (dragFile != null && dragFile.exists()) {
                    content.putFiles(java.util.Collections.singletonList(dragFile));
                } else if (dragText != null) {
                    content.putString(dragText);
                }
                
                db.setContent(content);
                event.consume();
            }
        });

        setOnDragDone(event -> {
            if (event.getTransferMode() == TransferMode.COPY) {
                // 文字拖走後，清空 UI
                this.setText("尚無訊息");
                this.dragText = null;
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
