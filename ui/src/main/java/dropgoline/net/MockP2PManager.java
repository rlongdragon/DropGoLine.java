package dropgoline.net;

import java.io.File;
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

        if (peers.contains(code)) return;
        peers.add(code);

        // 模擬「連上後」的即時事件
        if (listener != null) {
            listener.onIdChanged("MOCK-" + (System.currentTimeMillis() % 10000));
            listener.onPeerJoined(code);
        }

        // 模擬 1.5 秒後從 peer 收到歡迎訊息（從背景執行緒，模擬真實網路情境）
        new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ex) {}
            if (listener != null && peers.contains(code)) {
                listener.onMessageReceived(code, "嗨，我是 " + code + "！");
            }
        }).start();
    }

    @Override
    public void disconnect() {
        System.out.println("[Mock] disconnect");
        // 通知 UI 把所有 peer 移除
        Set<String> snapshot = new HashSet<>(peers);   // 複製一份避免邊走邊改
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
}