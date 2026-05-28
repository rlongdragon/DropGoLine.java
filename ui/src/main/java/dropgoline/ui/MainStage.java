package dropgoline.ui;

import java.util.HashMap;
import java.util.Map;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;

public class MainStage extends Stage{
    private final FlowPane cardPane;
    private final Map<String, ModernCard> cards = new HashMap<>();

    public MainStage(){
        setTitle("DropGoLine");
        setWidth(500);
        setHeight(400);

        cardPane = new FlowPane();
        cardPane.setHgap(12);
        cardPane.setVgap(12);
        cardPane.setPadding(new Insets(15));

        ScrollPane scrollPane = new ScrollPane(cardPane);
        scrollPane.setFitToWidth(true);

        Scene scene = new Scene(scrollPane);
        scene.getStylesheets().add(
            getClass().getResource("/styles/modern-card.css").toExternalForm()
        );
        setScene(scene);
    }

    public void addPeer(String name){
        if (cards.containsKey(name)){
            return;
        }
        ModernCard card = new ModernCard(name);
        card.setText("尚無訊息");
        cards.put(name, card);
        cardPane.getChildren().add(card);
    }

    public void removePeer(String name){
        ModernCard card = cards.remove(name);
        if (card != null){
            cardPane.getChildren().remove(card);
        }
    }
}
