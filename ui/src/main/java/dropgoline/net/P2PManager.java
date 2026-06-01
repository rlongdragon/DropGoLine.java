package dropgoline.net;

import java.io.File;

public interface P2PManager {
    void connect(String code);

    void disconnect();

    void sendText(String peerName, String text);

    void sendFile(String peerName, File file);

    void requestDownload(String peerName);

    void setListener(P2PListener listener);

    void broadcastText(String text);

    void broadcastFile(File file);
}
