package dropgoline.ui;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;

import javafx.application.Platform;
import javafx.stage.Stage;

/** 在 Windows 11 套用 Acrylic 背景；其他平台 / 失敗時自動跳過。 */
public final class WindowsAcrylic {

    private WindowsAcrylic() {}

    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_SYSTEMBACKDROP_TYPE = 38;
    private static final int DWMSBT_TRANSIENTWINDOW = 3;   // 3 = Acrylic

    private interface Dwmapi extends Library {
        int DwmSetWindowAttribute(HWND hwnd, int dwAttribute, IntByReference pvAttribute, int cbAttribute);
    }

    private static Dwmapi dwmapi;

    public static void apply(Stage stage) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;   // 非 Windows 跳過
        }
        new Thread(() -> {
            try {
                if (dwmapi == null) {
                    dwmapi = Native.load("dwmapi", Dwmapi.class, W32APIOptions.DEFAULT_OPTIONS);
                }

                HWND hwnd = findOwnWindow();
                if (hwnd == null) {
                    System.err.println("[Acrylic] 找不到視窗");
                    return;
                }

                dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE,
                        new IntByReference(1), 4);
                int hr = dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE,
                        new IntByReference(DWMSBT_TRANSIENTWINDOW), 4);

                if (hr == 0) {
                    System.out.println("[Acrylic] 已套用");
                    Platform.runLater(() ->
                        stage.getScene().getRoot().setStyle("-fx-background-color: rgba(30,30,30,0.55);"));
                } else {
                    System.out.println("[Acrylic] 系統不支援（可能非 Win11）,HRESULT=" + hr);
                }
            } catch (Throwable t) {
                System.err.println("[Acrylic] 套用失敗：" + t.getMessage());
            }
        }, "acrylic-apply").start();
    }

    /** 列舉頂層視窗,回傳「本 process 且可見」的那個（不靠標題，最可靠）。 */
    private static HWND findOwnWindow() throws InterruptedException {
        int myPid = Kernel32.INSTANCE.GetCurrentProcessId();
        final HWND[] result = { null };

        for (int attempt = 0; attempt < 10 && result[0] == null; attempt++) {
            User32.INSTANCE.EnumWindows((hWnd, data) -> {
                IntByReference pidRef = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hWnd, pidRef);
                if (pidRef.getValue() == myPid && User32.INSTANCE.IsWindowVisible(hWnd)) {
                    result[0] = hWnd;
                    return false;   // 找到，停止列舉
                }
                return true;        // 繼續
            }, Pointer.NULL);

            if (result[0] == null) Thread.sleep(100);
        }
        return result[0];
    }
}