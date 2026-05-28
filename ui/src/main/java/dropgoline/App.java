package dropgoline;

import dropgoline.net.MockP2PManager;
import dropgoline.net.P2PManager;
import dropgoline.ui.MainStage;

import javafx.application.Application;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage primaryStage) {
        // 之後換成 new RealP2PManager(...) 就完成整合
        P2PManager p2p = new MockP2PManager();

        MainStage mainStage = new MainStage(p2p);
        mainStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}