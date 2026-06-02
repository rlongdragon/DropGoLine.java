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

    // 暫時用環境變數切換 mock/real，方便開發時不依賴 signaling server
    // 跑時加 -DuseMock=true 就會用 mock，預設用 real
    private static final boolean USE_MOCK = Boolean.parseBoolean(
        System.getProperty("useMock", "false")
    );

    @Override
    public void start(Stage primaryStage) {
        P2PManager p2p;

        if (USE_MOCK) {
            System.out.println("[App] 使用 Mock 後端");
            p2p = new MockP2PManager();
        } else {
            p2p = buildRealP2P();
        }

        MainStage mainStage = new MainStage(p2p);
        mainStage.show();

        AppSettings s = AppSettings.current();
        String lastCode = s.getLastGroupCode();
        if (s.isEnableAutoReconnect() && lastCode != null && !lastCode.isBlank()) {
            System.out.println("[App] 嘗試重連上次房間：" + lastCode);
            p2p.connect(lastCode);
        }else {
            System.out.println("[App] 自動建立新房間");
            p2p.connect("");
        }
    }

    private P2PManager buildRealP2P() {
        AppSettings settings = AppSettings.current();

        // 1. 取得使用者名稱（DeviceName 空的話跳對話框）
        String name = settings.getDeviceName();
        if (name == null || name.isBlank()) {
            TextInputDialog dlg = new TextInputDialog("User");
            dlg.setTitle("設定使用者名稱");
            dlg.setHeaderText("第一次使用，請輸入你的名稱");
            dlg.setContentText("名稱：");
            name = dlg.showAndWait().orElse("User");
            settings.setDeviceName(name);
            settings.save();
        }

        String suffix = java.util.UUID.randomUUID().toString().substring(0, 4);
        String peerId = name + "#" + suffix;

        // 2. 組 signaling URL
        String serverIp = settings.getServerIP();
        if (serverIp == null || serverIp.isBlank()) {
            serverIp = "127.0.0.1";
        }
        String signalingUrl = "ws://" + serverIp + ":18080/signal";

        // 3. 下載目錄（用使用者 home / Downloads / DropGoLine）
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