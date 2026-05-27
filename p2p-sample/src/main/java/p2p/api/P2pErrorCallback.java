package p2p.api;

@FunctionalInterface
public interface P2pErrorCallback {
    void onError(String peerId, String reason);
}
