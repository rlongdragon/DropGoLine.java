package dropgoline.ui;

import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class ConnectionStage extends Stage {
    private final TextField codeField;
    private Consumer<String> onConnect;
    
    public ConnectionStage(){
        setTitle("建立連線");
        setResizable(false);

        Label label = new Label("輸入代碼");

        codeField = new TextField();
        codeField.setPromptText("例如：1234");

        Button connectBtn = new Button("建立");
        connectBtn.setOnAction(e -> handleConnect());

        VBox root = new VBox(12, label, codeField, connectBtn);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER);

        setScene(new Scene(root, 280, 160));
    }

    private void handleConnect(){
        String code = codeField.getText().trim();
        if (code.isEmpty()){
            Alert alert = new Alert(Alert.AlertType.ERROR, "請輸入代碼");
            alert.setHeaderText(null);
            alert.showAndWait();
            return;
        }
        if (onConnect != null){
            onConnect.accept(code);
        }
        close();
    }

    public void setOnConnect(Consumer<String> handler){
        this.onConnect = handler;
    }
}
