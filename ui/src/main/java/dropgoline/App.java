package dropgoline;

import java.nio.file.Path;
import java.nio.file.Paths;

import dropgoline.net.MockP2PManager;
import dropgoline.net.P2PManager;
import dropgoline.net.RealP2PManager;
import dropgoline.settings.AppSettings;
import dropgoline.ui.MainStage;

import javafx.application.Application;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Stage;

public class App extends Application {

    private static final boolean USE_MOCK = Boolean.parseBoolean(
            System.getProperty("useMock", "false"));

    @Override
    public void start(Stage primaryStage) {
        P2PManager p2p = buildP2PManager();

        MainStage mainStage = new MainStage(p2p, App::buildP2PManager);
        mainStage.show();

        AppSettings settings = AppSettings.current();
        String lastCode = settings.getLastGroupCode();
        if (settings.isEnableAutoReconnect() && lastCode != null && !lastCode.isBlank()) {
            System.out.println("[App] 嘗試重連上次房間：" + lastCode);
            p2p.connect(lastCode);
        } else {
            System.out.println("[App] 自動建立新房間");
            p2p.connect("");
        }
    }

    public static P2PManager buildP2PManager() {
        if (USE_MOCK) {
            System.out.println("[App] 使用 Mock 後端");
            return new MockP2PManager();
        }
        return buildRealP2P();
    }

    private static P2PManager buildRealP2P() {
        AppSettings settings = AppSettings.current();

        String name = settings.getDeviceName();
        if (name == null || name.isBlank()) {
            TextInputDialog dialog = new TextInputDialog("User");
            dialog.setTitle("設定使用者名稱");
            dialog.setHeaderText("第一次使用，請輸入你的名稱");
            dialog.setContentText("名稱：");
            name = dialog.showAndWait().orElse("User");
            settings.setDeviceName(name);
            settings.save();
        }

        String suffix = java.util.UUID.randomUUID().toString().substring(0, 4);
        String peerId = name + "#" + suffix;

        String serverIp = settings.getServerIP();
        if (serverIp == null || serverIp.isBlank()) {
            serverIp = "127.0.0.1";
        }
        String signalingUrl = "ws://" + serverIp.trim() + ":18080/signal";

        Path downloadDir = Paths.get(System.getProperty("user.home"), "Downloads", "DropGoLine");

        System.out.println("[App] 連線設定：");
        System.out.println("  peerId = " + peerId);
        System.out.println("  signaling = " + signalingUrl);
        System.out.println("  downloadDir = " + downloadDir);

        return new RealP2PManager(peerId, signalingUrl, downloadDir);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
