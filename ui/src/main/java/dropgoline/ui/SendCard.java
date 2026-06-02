package dropgoline.ui;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.scene.control.Label;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;

/** 固定在最上方的傳送卡片：拖檔案/文字進去 → 廣播給群組。 */
public class SendCard extends StackPane {
    private static final Pattern IMG_SRC_PATTERN = Pattern.compile(
            "<img[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    public SendCard(Consumer<List<File>> onFiles, Consumer<String> onText, Consumer<String> onImageSource,
            Runnable onHistoryRequest) {
        getStyleClass().add("send-card");
        setPrefSize(200, 90);

        Label label = new Label("將選取拖曳到這個區塊");
        label.getStyleClass().add("send-card-label");
        label.setWrapText(true);
        getChildren().add(label);

        setOnMouseClicked(e -> {
            if (onHistoryRequest != null) {
                onHistoryRequest.run();
            }
            e.consume();
        });

        setOnDragOver(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles() || db.hasUrl() || db.hasHtml() || db.hasString()) {
                e.acceptTransferModes(TransferMode.COPY);
                if (!getStyleClass().contains("drag-over")) {
                    getStyleClass().add("drag-over");
                }
            }
            e.consume();
        });

        setOnDragExited(e -> {
            getStyleClass().remove("drag-over");
            e.consume();
        });

        setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean done = false;
            if (db.hasFiles()) {
                onFiles.accept(db.getFiles());
                done = true;
            } else {
                Optional<String> imageSource = imageSourceFrom(db);
                if (imageSource.isPresent()) {
                    onImageSource.accept(imageSource.get());
                    done = true;
                } else if (db.hasString()) {
                    onText.accept(db.getString());
                    done = true;
                }
            }
            e.setDropCompleted(done);
            getStyleClass().remove("drag-over");
            e.consume();
        });
    }

    private Optional<String> imageSourceFrom(Dragboard db) {
        if (db.hasUrl() && isImageSource(db.getUrl())) {
            return Optional.of(db.getUrl());
        }
        if (db.hasHtml()) {
            Matcher matcher = IMG_SRC_PATTERN.matcher(db.getHtml());
            if (matcher.find() && isImageSource(matcher.group(1))) {
                return Optional.of(matcher.group(1));
            }
        }
        if (db.hasString() && isImageSource(db.getString())) {
            return Optional.of(db.getString().trim());
        }
        return Optional.empty();
    }

    private boolean isImageSource(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim().toLowerCase();
        if (text.startsWith("data:image/")) {
            return text.contains(";base64,");
        }
        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            return false;
        }
        int query = text.indexOf('?');
        if (query >= 0) {
            text = text.substring(0, query);
        }
        return text.endsWith(".png")
                || text.endsWith(".jpg")
                || text.endsWith(".jpeg")
                || text.endsWith(".gif")
                || text.endsWith(".bmp")
                || text.endsWith(".webp");
    }
}
