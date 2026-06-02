package dropgoline.net;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import p2p.api.P2p;
import p2p.api.P2pSessionInstance;

public class RealP2PManager implements P2PManager {

    private final String localName;
    private final String signalingUrl;
    private final Path downloadDir;

    private P2p p2p;
    private P2pSessionInstance group;
    private P2PListener listener;

    // 每個 peer 最近一筆檔案邀請的 offerId（requestDownload 要用）
    private final Map<String, String> latestOfferByPeer = new ConcurrentHashMap<>();

    // 已回報給 UI 的 peer，避免重複觸發 onPeerJoined
    private final Set<String> reportedPeers = ConcurrentHashMap.newKeySet();

    public RealP2PManager(String localName, String signalingUrl, Path downloadDir) {
        this.localName = localName;
        this.signalingUrl = signalingUrl;
        this.downloadDir = downloadDir;
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
        // 背景執行緒：建連線可能要數秒，不能卡 UI
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
                    String newCode = p2p.createGroup();      // 建立新 group
                    group = p2p.currentGroup();
                    System.out.println("[DropGoLine][P2PManager] group created id=" + newCode);
                    if (listener != null) listener.onIdChanged(newCode);
                } else {
                    System.out.println("[DropGoLine][P2PManager] joining group id=" + code);
                    group = p2p.joinGroup(code);             // 加入既有 group
                    System.out.println("[DropGoLine][P2PManager] group joined id=" + code);
                    if (listener != null) listener.onIdChanged(code);
                }

                attachEventListener();
                reportInitialMembers();   // 補抓加入前就在房裡的人
            } catch (Exception ex) {
                System.err.println("P2P 連線失敗：" + ex.getMessage());
                ex.printStackTrace();
            }
        }, "p2p-connect").start();
    }

    private void attachEventListener() {
        group.createReceivedListener(
            event -> {
                switch (event.type()) {
                    case MESSAGE -> {
                        System.out.println("[DropGoLine][P2PManager] message received from=" + event.from());
                        if (listener != null) {
                            listener.onMessageReceived(event.from(), event.message());
                        }
                    }
                    case FILE_OFFER -> {
                        System.out.println("[DropGoLine][P2PManager] file offer received from=" + event.from()
                                + ", offerId=" + event.offerId() + ", file=" + event.fileName()
                                + ", size=" + event.fileSize() + ", direct=" + event.direct());
                        latestOfferByPeer.put(event.from(), event.offerId());
                        if (listener != null) {
                            listener.onFileOffer(event.from(), event.fileName(), event.fileSize());
                        }
                    }
                    case FILE_SAVED -> {
                        Path file = event.file();
                        System.out.println("[DropGoLine][P2PManager] file saved from=" + event.from()
                                + ", offerId=" + event.offerId() + ", file=" + file
                                + ", direct=" + event.direct());
                        if (file != null && listener != null) {
                            listener.onTransferComplete(event.from(), file.toFile());
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

    /** 加入既有 group 時，已在房裡的人不觸發 PEER_JOINED，要主動補抓 */
    private void reportInitialMembers() {
        if (group == null) return;
        for (String member : group.showMembers()) {
            reportJoin(member);
        }
    }

    private void reportJoin(String peer) {
        if (peer == null || peer.isBlank() || peer.equals(localName)) return;
        if (reportedPeers.add(peer)) {   // add 回傳 true = 原本沒有，才通知
            if (listener != null) listener.onPeerJoined(peer);
        }
    }

    private void reportLeft(String peer) {
        if (peer == null) return;
        if (reportedPeers.remove(peer)) {
            latestOfferByPeer.remove(peer);
            if (listener != null) listener.onPeerLeft(peer);
        }
    }

    @Override
    public void disconnect() {
        for (String peer : new HashSet<>(reportedPeers)) {
            if (listener != null) listener.onPeerLeft(peer);
        }
        reportedPeers.clear();
        latestOfferByPeer.clear();

        if (p2p != null) {
            try {
                p2p.close();
            } catch (Exception ex) {
                // ignore
            }
            p2p = null;
            group = null;
        }
    }

    @Override
    public void sendText(String peerName, String text) {
        if (group == null) return;
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
            System.err.println("[P2P] 找不到 " + peerName + " 的待下載 offer");
            return;
        }
        try {
            group.save(offerId);    // 後端背景下載，完成時觸發 FILE_SAVED
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
        if (group == null) return;
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
