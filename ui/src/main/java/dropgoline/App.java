package dropgoline;

import dropgoline.ui.LayoutAnimator;

import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class App extends Application {

    private final LayoutAnimator animator = new LayoutAnimator();
    private boolean reversed = false;   // 用欄位記狀態（lambda 不能改區域變數，但能改欄位）

    @Override
    public void start(Stage stage) {
        Region box1 = makeBox("1", "#e74c3c");   // 紅
        Region box2 = makeBox("2", "#2ecc40");   // 綠
        Region box3 = makeBox("3", "#3498db");   // 藍

        // 初始位置（在 Pane 裡用 relocate 設絕對座標）
        box1.relocate(10, 10);
        box2.relocate(110, 10);
        box3.relocate(210, 10);

        // Pane 是「不自動排版」的容器，子節點位置全靠你自己設
        Pane canvas = new Pane(box1, box2, box3);
        canvas.setPrefSize(300, 100);

        Button shuffleBtn = new Button("交換頭尾方塊");
        shuffleBtn.setOnAction(e -> {
            if (!reversed) {
                animator.animate(box1, 210, 10);   // 紅 → 右
                animator.animate(box3, 10, 10);    // 藍 → 左
            } else {
                animator.animate(box1, 10, 10);
                animator.animate(box3, 210, 10);
            }
            reversed = !reversed;
        });

        VBox root = new VBox(15, canvas, shuffleBtn);
        root.setAlignment(Pos.CENTER);

        Scene scene = new Scene(root, 340, 180);
        stage.setTitle("LayoutAnimator 測試");
        stage.setScene(scene);
        stage.show();
    }

    // 做一個彩色方塊（中間有數字）
    private Region makeBox(String text, String color) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: white; -fx-font-size: 24px; -fx-font-weight: bold;");

        StackPane box = new StackPane(label);
        box.setMinSize(80, 80);
        box.setPrefSize(80, 80);
        box.setMaxSize(80, 80);
        box.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 8px;");
        return box;
    }

    public static void main(String[] args) {
        launch(args);
    }
}