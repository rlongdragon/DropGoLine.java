package dropgoline.ui;

import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class ConnectionStage extends Stage {

    private final TextField codeField;
    private Consumer<String> onConnect;

    public ConnectionStage() {
        setTitle("建立連線");
        setResizable(false);

        // === 建立新群組 ===
        Button createBtn = new Button("建立新群組");
        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setOnAction(e -> handleConnect(""));   // 空字串 = 建立

        Label createHint = new Label("產生新代碼，讓朋友用代碼加入");
        createHint.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");

        // === 加入既有群組 ===
        Label joinLabel = new Label("或輸入代碼加入既有群組：");
        codeField = new TextField();
        codeField.setPromptText("例如：JCMD");

        Button joinBtn = new Button("加入");
        joinBtn.setMaxWidth(Double.MAX_VALUE);
        joinBtn.setOnAction(e -> handleConnect(codeField.getText().trim()));

        VBox root = new VBox(10,
            createBtn,
            createHint,
            new Separator(),
            joinLabel,
            codeField,
            joinBtn
        );
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER);

        Scene scene = new Scene(root, 280, 240);
        scene.getStylesheets().add(
            getClass().getResource("/styles/app.css").toExternalForm()
        );
        setScene(scene);
    }

    private void handleConnect(String code) {
        if (onConnect != null) {
            onConnect.accept(code);   // 空字串 = 建立，非空 = 加入
        }
        close();
    }

    /** code 為空 → 建立新群組；非空 → 用代碼加入 */
    public void setOnConnect(Consumer<String> handler) {
        this.onConnect = handler;
    }
}