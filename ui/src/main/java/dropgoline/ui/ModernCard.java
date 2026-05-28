package dropgoline.ui;

import java.io.File;
import java.util.List;

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

    private Runnable onHistoryRequest;

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
        previewView.setPreserveRatio(true);
        previewView.setSmooth(true);

        transferBar = new ProgressBar();
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
    }

    private void setupDragReceiving() {
        setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasFiles() || db.hasString()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasFiles() || db.hasString()) {
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
                setFile(db.getFiles().get(0));
                success = true;
            } else if (db.hasString()) {
                setText(db.getString());
                success = true;
            }
            event.setDropCompleted(success);
            getStyleClass().remove("drag-over");
            event.consume();
        });
    }

    private void setupDragSource() {
        setOnDragDetected(event -> {
            System.out.println(">>> 偵測到拖曳, dragText=" + dragText + ", dragFile=" + dragFile);
            ClipboardContent content = new ClipboardContent();
            if (dragFile != null) {
                content.putFiles(List.of(dragFile)); 
            }else if (dragText != null && !dragText.isEmpty()){
                content.putString(dragText);
            }else{
                event.consume();
                return;
            }
            Dragboard db = startDragAndDrop(TransferMode.COPY);
            db.setContent(content);
            event.consume();
        });
    }

    private void setupContextMenu(){
        ContextMenu menu = new ContextMenu();

        MenuItem historyItem = new MenuItem("歷史紀錄");
        historyItem.setOnAction(e -> {
            if (onHistoryRequest != null){
                onHistoryRequest.run();
            }
        });
        menu.getItems().add(historyItem);

        setOnContextMenuRequested(event -> {
            menu.show(this, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    public void setText(String text){
        contentLabel.setText(text);
        layout.setCenter(contentLabel);
        dragText = text;
        dragFile = null;
    }

    public void setFile(File file){
        contentLabel.setText("📄 " + file.getName());
        layout.setCenter(contentLabel);
        dragFile = file;
        dragText = null;
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

    public void setOnHistoryRequest(Runnable handler){
        this.onHistoryRequest = handler;
    }

    public String getPeerName(){
        return peername;
    }
}
