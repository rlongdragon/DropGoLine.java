package dropgoline.ui;

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/** 讓無邊框視窗可以從邊緣/角落拖曳改變大小。 */
public final class ResizeHelper {

    private ResizeHelper() {}

    private static final int BORDER = 6;     // 邊緣感應寬度
    private static final double MIN_W = 320;
    private static final double MIN_H = 260;

    public static void install(Stage stage) {
        Scene scene = stage.getScene();
        final Cursor[] cur = { Cursor.DEFAULT };
        final double[] s = new double[6];   // screenX, screenY, w, h, stageX, stageY

        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            double x = e.getSceneX(), y = e.getSceneY();
            double w = scene.getWidth(), h = scene.getHeight();
            boolean l = x < BORDER, r = x > w - BORDER, t = y < BORDER, b = y > h - BORDER;
            if (l && t)      cur[0] = Cursor.NW_RESIZE;
            else if (r && t) cur[0] = Cursor.NE_RESIZE;
            else if (l && b) cur[0] = Cursor.SW_RESIZE;
            else if (r && b) cur[0] = Cursor.SE_RESIZE;
            else if (l)      cur[0] = Cursor.W_RESIZE;
            else if (r)      cur[0] = Cursor.E_RESIZE;
            else if (t)      cur[0] = Cursor.N_RESIZE;
            else if (b)      cur[0] = Cursor.S_RESIZE;
            else             cur[0] = Cursor.DEFAULT;
            scene.setCursor(cur[0]);
        });

        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (cur[0] != Cursor.DEFAULT) {
                s[0] = e.getScreenX(); s[1] = e.getScreenY();
                s[2] = stage.getWidth(); s[3] = stage.getHeight();
                s[4] = stage.getX(); s[5] = stage.getY();
            }
        });

        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            Cursor c = cur[0];
            if (c == Cursor.DEFAULT) return;          // 沒在邊緣 → 交給拖曳移動
            double dx = e.getScreenX() - s[0];
            double dy = e.getScreenY() - s[1];

            boolean east  = c == Cursor.E_RESIZE || c == Cursor.NE_RESIZE || c == Cursor.SE_RESIZE;
            boolean west  = c == Cursor.W_RESIZE || c == Cursor.NW_RESIZE || c == Cursor.SW_RESIZE;
            boolean south = c == Cursor.S_RESIZE || c == Cursor.SE_RESIZE || c == Cursor.SW_RESIZE;
            boolean north = c == Cursor.N_RESIZE || c == Cursor.NE_RESIZE || c == Cursor.NW_RESIZE;

            if (east)  stage.setWidth(Math.max(MIN_W, s[2] + dx));
            if (south) stage.setHeight(Math.max(MIN_H, s[3] + dy));
            if (west) {
                double nw = s[2] - dx;
                if (nw >= MIN_W) { stage.setX(s[4] + dx); stage.setWidth(nw); }
            }
            if (north) {
                double nh = s[3] - dy;
                if (nh >= MIN_H) { stage.setY(s[5] + dy); stage.setHeight(nh); }
            }
            e.consume();   // 邊緣拖曳時不要觸發「移動視窗」
        });
    }
}