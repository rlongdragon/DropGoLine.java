package dropgoline.ui;

import dropgoline.settings.AppSettings;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class SettingsStage extends Stage {
    private final TextField serverIpField;
    private final TextField deviceNameField;
    private final CheckBox autoCopyCheck;
    private final CheckBox autoSyncCheck;
    private final CheckBox autoReconnectCheck;
    private final CheckBox allowDiscoveryCheck;

    public SettingsStage(){
        setTitle("其他設定");
        setResizable(false);

        AppSettings settings = AppSettings.current();

        serverIpField = new TextField(settings.getServerIP());
        deviceNameField = new TextField(settings.getDeviceName());

        autoCopyCheck = new CheckBox("接收文字自動複製");
        autoCopyCheck.setSelected(settings.isAutoClipboardCopy());

        autoSyncCheck = new CheckBox("本機複製自動傳送");
        autoSyncCheck.setSelected(settings.isAutoClipboardSync());

        autoReconnectCheck = new CheckBox("啟動時自動重連好友");
        autoReconnectCheck.setSelected(settings.isEnableAutoReconnect());

        allowDiscoveryCheck = new CheckBox("允許好友自動連線");
        allowDiscoveryCheck.setSelected(settings.isAllowDiscovery());

        GridPane grid = new GridPane();
        grid.setVgap(10);
        grid.setHgap(10);
        grid.addRow(0, new Label("同步伺服器 IP"), serverIpField);
        grid.addRow(1, new Label("裝置名稱"), deviceNameField);

        Button saveBtn = new Button("儲存");
        saveBtn.setOnAction(e -> save());

        VBox root = new VBox(12,
                grid,
                autoCopyCheck,
                autoSyncCheck,
                autoReconnectCheck,
                allowDiscoveryCheck,
                saveBtn
            );
            root.setPadding(new Insets(20));

        Scene scene = new Scene(root);
        scene.getStylesheets().add(
            getClass().getResource("/styles/app.css").toExternalForm()
        );
        setScene(scene);
    }

    private void save(){
        AppSettings settings = AppSettings.current();
        settings.setServerIP(serverIpField.getText());
        settings.setDeviceName(deviceNameField.getText());
        settings.setAutoClipboardCopy(autoCopyCheck.isSelected());
        settings.setAutoClipboardSync(autoSyncCheck.isSelected());
        settings.setEnableAutoReconnect(autoReconnectCheck.isSelected());
        settings.setAllowDiscovery(allowDiscoveryCheck.isSelected());
        settings.save();

        Alert alert = new Alert(Alert.AlertType.INFORMATION, "設定已儲存！");
        alert.setHeaderText(null);
        alert.showAndWait();
        close();
    }
}
