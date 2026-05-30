package dropgoline.ui;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;

public class WindowsAcrylic {
    private WindowsAcrylic() {}

    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20; // Windows 10 1809+ / Windows 11
    private static final int DWMWA_SYSTEMBACKDROP_TYPE = 38;     // Windows 11 22H2+
    private static final int DWMSBT_TRANSIENTWINDOW = 3;          // Acrylic 模式

    private interface Dwmapi extends Library{
        int DwmSetWindowAttribute(HWND hwnd, int dwAttribute, IntByReference pvAttribute, int cbAttribute);
    }

    private static Dwmapi dwmapi;

    /**
     * 嘗試套用 acrylic。
     * @param windowTitle 視窗標題,用來找到原生視窗 handle (HWND)
     * @return 真的套用成功才回 true（= Win11 且呼叫成功）;否則 false
     */

    public static boolean apply(String windowTitle){
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return false;
        }
        try{
            if (dwmapi == null) {
                dwmapi = Native.load("dwmapi", Dwmapi.class, W32APIOptions.DEFAULT_OPTIONS);
            }

            HWND hwnd = User32.INSTANCE.FindWindow(null, windowTitle);
            if (hwnd == null) {
                System.err.println("[Acrylic] 找不到視窗: " + windowTitle);
                return false;
            }

            dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, new IntByReference(1), 4);

            int hr = dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, new IntByReference(DWMSBT_TRANSIENTWINDOW), 4);

            if (hr == 0){
                System.out.println("[Acrylic] 已套用");
                return true;
            } else {
                System.out.println("[Acrylic] 系統不支援（可能非 Win11）,HRESULT=" + hr);
                return false;
            }
        }catch (Throwable t){
            System.err.println("[Acrylic] 套用失敗: " + t.getMessage());
            return false;
        }
    }
}
