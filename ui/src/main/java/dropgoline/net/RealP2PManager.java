package dropgoline.net;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dropgoline.historyservice.HistoryManager;
import dropgoline.util.PeerIds;
import p2p.api.P2p;
import p2p.api.P2pSessionInstance;
import p2p.quic.QuicChannel;

public class RealP2PManager implements P2PManager {

    private final Map<String, QuicChannel> peerChannels = new ConcurrentHashMap<>();
    private final String localName;
    private final String signalingUrl;
    private final Path downloadDir;
    private final Map<String, String> latestOfferByPeer = new ConcurrentHashMap<>();
    private final Set<String> reportedPeers = ConcurrentHashMap.newKeySet();

    private P2p p2p;
    private P2pSessionInstance group;
    private P2PListener listener;

    public RealP2PManager(String localName, String signalingUrl, Path downloadDir) {
        this.localName = localName;
        this.signalingUrl = signalingUrl;
        this.downloadDir = downloadDir;
    }

    public QuicChannel getChannel(String peerName) {
        return peerChannels.get(peerName);
    }

    public void registerChannel(String peerName, QuicChannel channel) {
        peerChannels.put(peerName, channel);
    }

    @Override
    public void setListener(P2PListener listener) {
        this.listener = listener;
    }

    @Override
    public void connect(String code) {
        System.out.println("[DropGoLine][P2PManager] connect requested code=" + code
                + ", localName=" + localName + ", signalingUrl=" + signalingUrl
                + ", downloadDir=" + downloadDir);
        new Thread(() -> {
            try {
                System.out.println("[DropGoLine][P2PManager] creating download directory " + downloadDir);
                Files.createDirectories(downloadDir);

                if (p2p == null) {
                    System.out.println("[DropGoLine][P2PManager] connecting to signaling server");
                    p2p = P2p.connect(localName, signalingUrl, downloadDir);
                }

                if (code == null || code.isBlank()) {
                    System.out.println("[DropGoLine][P2PManager] creating group");
                    createNewGroup();
                } else {
                    try {
                        System.out.println("[DropGoLine][P2PManager] joining group id=" + code);
                        group = p2p.joinGroup(code);
                        System.out.println("[DropGoLine][P2PManager] group joined id=" + code);
                        if (listener != null) {
                            listener.onIdChanged(code);
                        }
                    } catch (Exception joinEx) {
                        System.err.println("[P2P] join " + code + " failed, creating a new group: "
                                + joinEx.getMessage());
                        createNewGroup();
                    }
                }
                attachEventListener();
                reportInitialMembers();
            } catch (Exception ex) {
                System.err.println("P2P connect failed: " + ex.getMessage());
                ex.printStackTrace();
            }
        }, "p2p-connect").start();
    }

    private void attachEventListener() {
        group.createReceivedListener(
            event -> {
                switch (event.type()) {
                    case MESSAGE -> {
                        String from = PeerIds.canonicalize(event.from());
                        System.out.println("[DropGoLine][P2PManager] message received from=" + from);
                        HistoryManager.getInstance().addHistory(from, event.message(), true, "TEXT");
                        if (listener != null) {
                            listener.onMessageReceived(from, event.message());
                        }
                    }
                    case FILE_OFFER -> {
                        String from = PeerIds.canonicalize(event.from());
                        System.out.println("[DropGoLine][P2PManager] file offer received from=" + from
                                + ", offerId=" + event.offerId() + ", file=" + event.fileName()
                                + ", size=" + event.fileSize() + ", direct=" + event.direct());
                        latestOfferByPeer.put(from, event.offerId());
                        if (listener != null) {
                            listener.onFileOffer(from, event.fileName(), event.fileSize());
                        }
                    }
                    case FILE_SAVED -> {
                        String from = PeerIds.canonicalize(event.from());
                        Path file = event.file();
                        System.out.println("[DropGoLine][P2PManager] file saved from=" + from
                                + ", offerId=" + event.offerId() + ", file=" + file
                                + ", direct=" + event.direct());
                        if (file != null) {
                            HistoryManager.getInstance().addHistory(from, file.toString(), true, "FILE");
                        }
                        if (file != null && listener != null) {
                            listener.onTransferComplete(from, file.toFile());
                        }
                    }
                    case PEER_JOINED -> reportJoin(event.from());
                    case PEER_LEFT -> reportLeft(event.from());
                    case NOTICE -> System.out.println("[P2P notice] " + event.from() + ": " + event.message());
                }
            },
            (peerId, reason) -> System.err.println("[P2P error] " + peerId + ": " + reason)
        );
    }

    private void reportInitialMembers() {
        if (group == null) {
            return;
        }
        for (String member : group.showMembers()) {
            reportJoin(member);
        }
    }

    private void reportJoin(String peer) {
        String normalizedPeer = PeerIds.canonicalize(peer);
        if (normalizedPeer.isBlank() || normalizedPeer.equals(PeerIds.canonicalize(localName))) {
            return;
        }
        if (reportedPeers.add(normalizedPeer) && listener != null) {
            listener.onPeerJoined(normalizedPeer);
        }
    }

    private void reportLeft(String peer) {
        String normalizedPeer = PeerIds.canonicalize(peer);
        if (normalizedPeer.isBlank()) {
            return;
        }
        if (reportedPeers.remove(normalizedPeer)) {
            latestOfferByPeer.remove(normalizedPeer);
            if (listener != null) {
                listener.onPeerLeft(normalizedPeer);
            }
        }
    }

    private void createNewGroup() throws Exception {
        String newCode = p2p.createGroup();
        group = p2p.currentGroup();
        System.out.println("[DropGoLine][P2PManager] group created id=" + newCode);
        if (listener != null) {
            listener.onIdChanged(newCode);
        }
    }

    @Override
    public void disconnect() {
        for (String peer : new HashSet<>(reportedPeers)) {
            if (listener != null) {
                listener.onPeerLeft(peer);
            }
        }
        reportedPeers.clear();
        latestOfferByPeer.clear();

        if (p2p != null) {
            try {
                p2p.close();
            } catch (Exception ex) {
                // Ignore cleanup errors.
            }
            p2p = null;
            group = null;
        }
    }

    @Override
    public void sendText(String peerName, String text) {
        if (group == null) {
            return;
        }
        try {
            group.send(text, null, peerName);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void sendFile(String peerName, File file) {
        System.out.println("[DropGoLine][P2PManager] sendFile requested peer=" + peerName
                + ", file=" + file.getAbsolutePath() + ", size=" + file.length()
                + ", groupReady=" + (group != null));
        if (group == null) {
            System.out.println("[DropGoLine][P2PManager] sendFile skipped because group is null");
            return;
        }
        try {
            group.send(null, file.toPath(), peerName);
            System.out.println("[DropGoLine][P2PManager] sendFile offer submitted peer=" + peerName
                    + ", file=" + file.getName());
        } catch (Exception ex) {
            System.err.println("[DropGoLine][P2PManager] sendFile failed peer=" + peerName
                    + ", file=" + file.getAbsolutePath() + ": " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    @Override
    public void requestDownload(String peerName) {
        String offerId = latestOfferByPeer.get(peerName);
        System.out.println("[DropGoLine][P2PManager] requestDownload peer=" + peerName
                + ", offerId=" + offerId + ", groupReady=" + (group != null));
        if (offerId == null || group == null) {
            System.err.println("[P2P] no pending offer for " + peerName);
            return;
        }
        try {
            group.save(offerId);
            System.out.println("[DropGoLine][P2PManager] requestDownload submitted peer=" + peerName
                    + ", offerId=" + offerId);
        } catch (Exception ex) {
            System.err.println("[DropGoLine][P2PManager] requestDownload failed peer=" + peerName
                    + ", offerId=" + offerId + ": " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    @Override
    public void broadcastText(String text) {
        if (group == null) {
            return;
        }
        try {
            group.send(text);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void broadcastFile(File file) {
        System.out.println("[DropGoLine][P2PManager] broadcastFile requested file="
                + file.getAbsolutePath() + ", size=" + file.length()
                + ", groupReady=" + (group != null));
        if (group == null) {
            System.out.println("[DropGoLine][P2PManager] broadcastFile skipped because group is null");
            return;
        }
        try {
            group.send(file.toPath());
            System.out.println("[DropGoLine][P2PManager] broadcastFile offer submitted file=" + file.getName());
        } catch (Exception ex) {
            System.err.println("[DropGoLine][P2PManager] broadcastFile failed file="
                    + file.getAbsolutePath() + ": " + ex.getMessage());
            ex.printStackTrace();
        }
    }
}
