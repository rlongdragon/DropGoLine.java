package dropgoline;

import java.util.List;

import dropgoline.model.HistoryItem;
import dropgoline.ui.HistoryStage;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage stage) {
        Button openHistoryBtn = new Button("開啟歷史紀錄");
        openHistoryBtn.setOnAction(e -> {
            // 假資料（之後會由開發者 A 的 HistoryManager 提供真資料）
            List<HistoryItem> fakeHistory = List.of(
                new HistoryItem("早安！", "TEXT", "09:01"),
                new HistoryItem("幫我看一下這個檔案", "FILE", "09:05"),
                new HistoryItem("收到，謝謝", "TEXT", "09:06"),
                new HistoryItem("晚點再傳你另一份", "TEXT", "09:10"),
                new HistoryItem("好的沒問題", "TEXT", "09:11"),
                new HistoryItem("這是第六筆，測試捲動", "TEXT", "09:20"),
                new HistoryItem("第七筆", "TEXT", "09:21"),
                new HistoryItem("第八筆，應該要能捲了", "TEXT", "09:22")
            );

            HistoryStage history = new HistoryStage("Harry's Mac", fakeHistory);
            history.show();
        });

        Scene scene = new Scene(new StackPane(openHistoryBtn), 300, 200);
        stage.setTitle("DropGoLine");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}