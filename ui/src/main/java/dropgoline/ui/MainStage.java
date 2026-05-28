package dropgoline.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dropgoline.net.P2PListener;
import dropgoline.net.P2PManager;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;

public class MainStage extends Stage implements P2PListener {
    private final P2PManager p2p;
    private final FlowPane cardPane;
    private final Map<String, ModernCard> cards = new HashMap<>();
    private final Label idLabel;

    public MainStage(P2PManager p2p){
        this.p2p = p2p;

        setTitle("DropGoLine");
        setWidth(500);
        setHeight(400);

        cardPane = new FlowPane();
        cardPane.setHgap(12);
        cardPane.setVgap(12);
        cardPane.setPadding(new Insets(15));

        ScrollPane scrollPane = new ScrollPane(cardPane);
        scrollPane.setFitToWidth(true);

        MenuBar menuBar = buildMenuBar();

        idLabel = new Label("ID: -");
        idLabel.setPadding(new Insets(4, 10, 4, 10));

        BorderPane root = new BorderPane();
        root.setTop(menuBar);
        root.setCenter(scrollPane);
        root.setBottom(idLabel);

        Scene scene = new Scene(root);
        scene.getStylesheets().add(
            getClass().getResource("/styles/modern-card.css").toExternalForm()
        );
        setScene(scene);

        p2p.setListener(this);
    }

    private MenuBar buildMenuBar(){
        MenuItem connectItem = new MenuItem("建立連線");
        connectItem.setOnAction(e -> handleConnect());

        MenuItem disconnectItem = new MenuItem("斷開連線");
        disconnectItem.setOnAction(e -> p2p.disconnect());
    
        MenuItem settingsItem = new MenuItem("其他設定");
        settingsItem.setOnAction(e -> new SettingsStage().show());
        
        Menu optionsMenu = new Menu("選項");
        optionsMenu.getItems().addAll(connectItem, disconnectItem, new SeparatorMenuItem(), settingsItem);

        return new MenuBar(optionsMenu);
    }

    private void handleConnect(){
        ConnectionStage conn = new ConnectionStage();
        conn.setOnConnect(code -> p2p.connect(code));
        conn.show();
    }
    
    private void openHistoryFor(String peerName){
        HistoryStage history = new HistoryStage(peerName, List.of());
        history.show();
    }
    
    @Override
    public void onIdChanged(String id) {
        Platform.runLater(() -> idLabel.setText("ID: " + id));
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
            if (card != null){
                card.setText(text);
            }
        });
    }
    
    @Override
    public void onTransferProgress(String peerName, double progress) {
        Platform.runLater(() -> {
            ModernCard card = cards.get(peerName);
            if (card != null){
                card.setProgress(progress);
            }
        });
    }
    private void addPeer(String name){
        if (cards.containsKey(name)){
            return;
        }
        ModernCard card = new ModernCard(name);
        card.setText("尚無訊息");
        card.setOnHistoryRequest(() -> openHistoryFor(name));

        cards.put(name, card);
        cardPane.getChildren().add(card);
    }

    private void removePeer(String name){
        ModernCard card = cards.remove(name);
        if (card != null){
            cardPane.getChildren().remove(card);
        }
    }
}
