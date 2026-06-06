package dropgoline.net;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
    private final Map<String, RepeatedLog> repeatedErrors = new ConcurrentHashMap<>();
    private final Set<String> reportedPeers = ConcurrentHashMap.newKeySet();
    private final Object connectLock = new Object();
    private final AtomicLong connectRequests = new AtomicLong();

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
        System.out.println("[DropGoLine][P2PManager] listener set="
                + (listener == null ? "null" : listener.getClass().getName()));
    }

    @Override
    public void connect(String code) {
        long requestId = connectRequests.incrementAndGet();
        System.out.println("[DropGoLine][P2PManager] connect requested code=" + code
                + ", requestId=" + requestId
                + ", latestRequestId=" + connectRequests.get()
                + ", localName=" + localName + ", signalingUrl=" + signalingUrl
                + ", downloadDir=" + downloadDir);
        new Thread(() -> {
            try {
                synchronized (connectLock) {
                    if (requestId != connectRequests.get()) {
                        System.out.println("[DropGoLine][P2PManager] connect skipped stale request code=" + code);
                        return;
                    }

                    System.out.println("[DropGoLine][P2PManager] creating download directory " + downloadDir);
                    Files.createDirectories(downloadDir);

                    if (p2p == null) {
                        System.out.println("[DropGoLine][P2PManager] connecting to signaling server");
                        p2p = P2p.connect(localName, signalingUrl, downloadDir);
                    }

                    if (code == null || code.isBlank()) {
                        System.out.println("[DropGoLine][P2PManager] creating group");
                        createNewGroup(null);
                    } else {
                        try {
                            System.out.println("[DropGoLine][P2PManager] joining group id=" + code);
                            group = p2p.joinGroup(code);
                            System.out.println("[DropGoLine][P2PManager] group joined id=" + group.groupId()
                                    + ", members=" + group.showMembers());
                            if (listener != null) {
                                System.out.println("[DropGoLine][P2PManager] notifying id changed id=" + group.groupId());
                                listener.onIdChanged(group.groupId());
                            } else {
                                System.out.println("[DropGoLine][P2PManager] id changed skipped because listener is null");
                            }
                        } catch (Exception joinEx) {
                            System.err.println("[P2P] join " + code + " failed, recreating that group id: "
                                    + joinEx.getMessage());
                            createNewGroup(code);
                        }
                    }
                    if (requestId != connectRequests.get()) {
                        System.out.println("[DropGoLine][P2PManager] connect result ignored stale request code=" + code);
                        return;
                    }
                    attachEventListener();
                    reportInitialMembers();
                    System.out.println("[DropGoLine][P2PManager] connect completed code=" + code
                            + ", group=" + (group == null ? "null" : group.groupId())
                            + ", reportedPeers=" + reportedPeers);
                }
            } catch (Exception ex) {
                System.err.println("P2P connect failed: " + ex.getMessage());
                ex.printStackTrace();
            }
        }, "p2p-connect").start();
    }

    private void attachEventListener() {
        System.out.println("[DropGoLine][P2PManager] attaching event listener group="
                + (group == null ? "null" : group.groupId())
                + ", currentMembers=" + (group == null ? "[]" : group.showMembers())
                + ", uiListener=" + (listener == null ? "null" : listener.getClass().getName()));
        group.createReceivedListener(
            event -> {
                System.out.println("[DropGoLine][P2PManager] event received type=" + event.type()
                        + ", from=" + event.from()
                        + ", direct=" + event.direct()
                        + ", thread=" + Thread.currentThread().getName());
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
                            HistoryManager.getInstance().addHistory(from, file.toString(), true,
                                    isImageFileName(file.getFileName().toString()) ? "IMAGE" : "FILE");
                        }
                        if (file != null && listener != null) {
                            listener.onTransferComplete(from, file.toFile());
                        }
                    }
                    case FILE_PROGRESS -> {
                        String from = PeerIds.canonicalize(event.from());
                        if (listener != null && event.fileSize() > 0 && event.bytesTransferred() >= 0) {
                            double progress = Math.min(1.0,
                                    Math.max(0.0, event.bytesTransferred() / (double) event.fileSize()));
                            listener.onTransferProgress(from, progress);
                        }
                    }
                    case PEER_JOINED -> reportJoin(event.from());
                    case PEER_LEFT -> reportLeft(event.from());
                    case NOTICE -> System.out.println("[P2P notice] " + event.from() + ": " + event.message());
                }
            },
            this::logP2pError
        );
    }

    private void logP2pError(String peerId, String reason) {
        String peer = PeerIds.canonicalize(peerId);
        if (peer.isBlank()) {
            peer = "unknown";
        }
        String message = shortLogMessage(reason);
        String key = peer + "\n" + message;
        int count = repeatedErrors.compute(key, (ignored, previous) ->
                previous == null ? new RepeatedLog(1) : previous.increment()).count();
        System.err.println("[P2P error] " + peer + ": " + message + " (x" + count + ")");
    }

    private String shortLogMessage(String message) {
        if (message == null || message.isBlank()) {
            return "connection failed";
        }
        String normalized = message
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        int maxLength = 180;
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) + "..." : normalized;
    }

    private void reportInitialMembers() {
        if (group == null) {
            System.out.println("[DropGoLine][P2PManager] reportInitialMembers skipped because group is null");
            return;
        }
        System.out.println("[DropGoLine][P2PManager] reportInitialMembers group=" + group.groupId()
                + ", members=" + group.showMembers());
        for (String member : group.showMembers()) {
            reportJoin(member);
        }
    }

    private void reportJoin(String peer) {
        String normalizedPeer = PeerIds.canonicalize(peer);
        System.out.println("[DropGoLine][P2PManager] reportJoin peer=" + normalizedPeer
                + ", local=" + PeerIds.canonicalize(localName)
                + ", alreadyReported=" + reportedPeers.contains(normalizedPeer)
                + ", listener=" + (listener == null ? "null" : listener.getClass().getName()));
        if (normalizedPeer.isBlank() || normalizedPeer.equals(PeerIds.canonicalize(localName))) {
            System.out.println("[DropGoLine][P2PManager] reportJoin ignored peer=" + normalizedPeer);
            return;
        }
        boolean added = reportedPeers.add(normalizedPeer);
        if (added && listener != null) {
            System.out.println("[DropGoLine][P2PManager] reportJoin notifying UI peer=" + normalizedPeer);
            listener.onPeerJoined(normalizedPeer);
        } else {
            System.out.println("[DropGoLine][P2PManager] reportJoin not notified peer=" + normalizedPeer
                    + ", added=" + added
                    + ", listenerPresent=" + (listener != null));
        }
    }

    private void reportLeft(String peer) {
        String normalizedPeer = PeerIds.canonicalize(peer);
        System.out.println("[DropGoLine][P2PManager] reportLeft peer=" + normalizedPeer
                + ", wasReported=" + reportedPeers.contains(normalizedPeer)
                + ", listener=" + (listener == null ? "null" : listener.getClass().getName()));
        if (normalizedPeer.isBlank()) {
            return;
        }
        if (reportedPeers.remove(normalizedPeer)) {
            latestOfferByPeer.remove(normalizedPeer);
            if (listener != null) {
                System.out.println("[DropGoLine][P2PManager] reportLeft notifying UI peer=" + normalizedPeer);
                listener.onPeerLeft(normalizedPeer);
            }
        } else {
            System.out.println("[DropGoLine][P2PManager] reportLeft ignored unreported peer=" + normalizedPeer);
        }
    }

    private void createNewGroup(String requestedCode) throws Exception {
        String newCode = requestedCode == null || requestedCode.isBlank()
                ? p2p.createGroup()
                : p2p.createGroup(requestedCode);
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

    private boolean isImageFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".gif")
                || name.endsWith(".bmp")
                || name.endsWith(".webp");
    }

    private record RepeatedLog(int count) {
        private RepeatedLog increment() {
            return new RepeatedLog(count + 1);
        }
    }
}
