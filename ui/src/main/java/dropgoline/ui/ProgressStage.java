package dropgoline.ui;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.application.Platform;

public class ProgressStage extends Stage{
    private final Label statusLabel;
    private final ProgressBar progressBar;

    public ProgressStage(String fileName){
        setTitle("Downloading " + fileName);
        initStyle(StageStyle.UTILITY);
        setAlwaysOnTop(true);
        setResizable(false);

        statusLabel = new Label("Preparing: " + fileName);

        progressBar = new ProgressBar();
        progressBar.setPrefWidth(300);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        VBox root = new VBox(10);
        root.setPadding(new Insets(15));
        root.getChildren().addAll(statusLabel, progressBar);

        Scene scene = new Scene(root, 350, 100);
        setScene(scene);
    }

    public void updateProgress(double progress){
        Platform.runLater(() -> {
            if (progress < 0) {
                progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                statusLabel.setText("Waiting for connection...");
            } else {
                progressBar.setProgress(progress);
                int percent = (int) (progress * 100);
                statusLabel.setText("Downloading..." + percent + "%");
            }
        });
    }
}
