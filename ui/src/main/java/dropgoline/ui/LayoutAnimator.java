package dropgoline.ui;

import java.util.HashMap;
import java.util.Map;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.util.Duration;

public class LayoutAnimator {
    private static final Duration DURATION = Duration.millis(400);

    private final Map<Node, Timeline> running = new HashMap<>();

    public void animate(Node node, double targetX, double targetY){
        Timeline old = running.get(node);
        if (old != null){
            old.stop();
        }

        KeyValue kvX = new KeyValue(node.layoutXProperty(), targetX, Interpolator.EASE_OUT);
        KeyValue kvY = new KeyValue(node.layoutYProperty(), targetY, Interpolator.EASE_OUT);
        KeyFrame frame = new KeyFrame(DURATION, kvX, kvY);

        Timeline timeline = new Timeline(frame);
        timeline.setOnFinished(e -> running.remove(node));
        running.put(node, timeline);
        timeline.play();
    }
}
