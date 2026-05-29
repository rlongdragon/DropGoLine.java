package dropgoline.ui;

import java.util.List;

import dropgoline.model.HistoryItem;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class HistoryStage extends Stage{
    private final VBox itemBox;
    private final ScrollPane scrollPane;

    public HistoryStage(String peerName, List<HistoryItem> items){
        setTitle(peerName + " 的歷史紀錄");
        setWidth(340);
        setHeight(500);

        itemBox = new VBox(8);
        itemBox.setPadding(new Insets(10));

        scrollPane = new ScrollPane(itemBox);
        scrollPane.setFitToWidth(true);

        Scene scene = new Scene(scrollPane);
        scene.getStylesheets().addAll(
            getClass().getResource("/styles/app.css").toExternalForm(),
            getClass().getResource("/styles/modern-card.css").toExternalForm()
        );
        setScene(scene);

        for (HistoryItem item : items){
            addItem(item);
        }

        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE){
                close();
            }
        });
    }

    public void addItem(HistoryItem item){
        ModernCard card = new ModernCard(item.timestamp());
        card.setText(item.content());
        card.setPrefHeight(70);
        card.setMinHeight(70);
        card.setMaxWidth(Double.MAX_VALUE);

        itemBox.getChildren().add(card);

        Platform.runLater(() -> {
            scrollPane.setVvalue(1.0);
        });
    }
    
}
