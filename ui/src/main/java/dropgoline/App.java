package dropgoline;

import dropgoline.ui.MainStage;

import javafx.application.Application;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage primaryStage) {
        MainStage mainStage = new MainStage();

        // 先加幾個假好友測試版面（之後會由後端通知時才動態新增）
        mainStage.addPeer("Alice");
        mainStage.addPeer("Bob");
        mainStage.addPeer("Carol");

        mainStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}