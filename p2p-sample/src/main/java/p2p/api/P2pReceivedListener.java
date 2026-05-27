package p2p.api;

@FunctionalInterface
public interface P2pReceivedListener {
    void onReceived(P2pEvent event);
}
