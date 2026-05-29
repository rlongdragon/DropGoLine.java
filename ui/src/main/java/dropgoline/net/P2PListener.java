package dropgoline.net;

import java.io.File;

public interface P2PListener {
    void onIdChanged(String id);

    void onPeerJoined(String peerName);

    void onPeerLeft(String peerName);

    void onMessageReceived(String peerName, String text);

    void onTransferProgress(String peerName, double progress);

    void onFileOffer(String peerName, String fileName, long fileSize);

    void onTransferComplete(String peerName, File file);
}
