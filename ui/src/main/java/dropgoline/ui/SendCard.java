package dropgoline.ui;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.Label;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;

/** 固定在最上方的傳送卡片：拖檔案/文字進去 → 廣播給群組。 */
public class SendCard extends StackPane {

    public SendCard(Consumer<List<File>> onFiles, Consumer<String> onText) {
        getStyleClass().add("send-card");
        setPrefSize(200, 90);

        Label label = new Label("將選取拖曳到這個區塊");
        label.getStyleClass().add("send-card-label");
        label.setWrapText(true);
        getChildren().add(label);

        setOnDragOver(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles() || db.hasString()) {
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
            } else if (db.hasString()) {
                onText.accept(db.getString());
                done = true;
            }
            e.setDropCompleted(done);
            getStyleClass().remove("drag-over");
            e.consume();
        });
    }
}