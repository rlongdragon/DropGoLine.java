package dropgoline.net;

public interface P2PListener {
    void onIdChanged(String id);

    void onPeerJoined(String peerName);

    void onPeerLeft(String peerName);

    void onMessageReceived(String peerName, String text);

    void onTransferProgress(String peerName, double progress);
}
