package dropgoline.net;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;


public class MockP2PManager implements P2PManager {


    private P2PListener listener;
    private final Set<String> peers = new HashSet<>();

    @Override
    public void setListener(P2PListener listener) {
        this.listener = listener;
    }

    @Override
    public void connect(String code) {
        System.out.println("[Mock] connect: " + code);
        if (peers.contains(code))
            return;
        peers.add(code);

        if (listener != null) {
            listener.onIdChanged("MOCK-" + (System.currentTimeMillis() % 10000));
            listener.onPeerJoined(code);
        }

        // 開背景執行緒模擬：1.5s 後傳歡迎訊息、2.5s 後傳檔案邀請
        new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ex) {
            }
            if (listener != null && peers.contains(code)) {
                listener.onMessageReceived(code, "嗨，我是 " + code + "！");
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
            }
            if (listener != null && peers.contains(code)) {
                listener.onFileOffer(code, "example.zip", 1_500_000);
            }
        }).start();
    }

    @Override
    public void disconnect() {
        System.out.println("[Mock] disconnect");
        Set<String> snapshot = new HashSet<>(peers);
        peers.clear();
        if (listener != null) {
            for (String p : snapshot) {
                listener.onPeerLeft(p);
            }
        }
    }

    @Override
    public void sendText(String peerName, String text) {
        System.out.println("[Mock] sendText to " + peerName + ": " + text);
    }

    @Override
    public void sendFile(String peerName, File file) {
        System.out.println("[Mock] sendFile to " + peerName + ": " + file.getName());
    }

    @Override
    public void requestDownload(String peerName) {
        System.out.println("[Mock] requestDownload from: " + peerName);

        // 開背景執行緒模擬下載：分 30 步、每步 50ms 進度回報、最後寫一個 temp 檔
        new Thread(() -> {
            try {
                for (int i = 0; i <= 30; i++) {
                    Thread.sleep(50);
                    double progress = i / 30.0;
                    if (listener != null) {
                        listener.onTransferProgress(peerName, progress);
                    }
                }

                // 建立一個真實的 temp 檔（讓拖到 Explorer 後真的能複製出去）
                File tempFile = new File(
                        System.getProperty("java.io.tmpdir"),
                        "DropGoLine_" + System.currentTimeMillis() + ".txt");
                try (FileWriter fw = new FileWriter(tempFile)) {
                    fw.write("Mock 下載完成的內容\n來自 peer: " + peerName);
                }

                if (listener != null) {
                    listener.onTransferComplete(peerName, tempFile);
                }
            } catch (InterruptedException | IOException ex) {
                ex.printStackTrace();
            }
        }).start();
    }

    @Override
    public void broadcastText(String text) {
        System.out.println("[Mock] 廣播文字：" + text);
    }

    @Override
    public void broadcastFile(File file) {
        System.out.println("[Mock] 廣播檔案：" + file.getName());
    }
}