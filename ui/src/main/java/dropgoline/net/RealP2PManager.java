package dropgoline.net;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import p2p.transfer.FileTransferService;
import p2p.transfer.FileTransferProtocol;
import p2p.transfer.ChecksumService;

import p2p.api.P2p;
import p2p.api.P2pSessionInstance;
import p2p.quic.QuicChannel;
import dropgoline.historyservice.HistoryManager;

public class RealP2PManager implements P2PManager {
  
    


    private final Map<String, QuicChannel> peerChannels = new ConcurrentHashMap<>();

    // 這裡是正確的：在類別裡面
    public QuicChannel getChannel(String peerName) {
        return peerChannels.get(peerName);
    }

    // 這裡是正確的：在類別裡面
    public void registerChannel(String peerName, QuicChannel channel) {
        peerChannels.put(peerName, channel);
    }//

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
        // 背景執行緒：建連線可能要數秒，不能卡 UI
        new Thread(() -> {
            try {
                Files.createDirectories(downloadDir);

                if (p2p == null) {
                    p2p = P2p.connect(localName, signalingUrl, downloadDir);
                }

                if (code == null || code.isBlank()) {
                    String newCode = p2p.createGroup();      // 建立新 group
                    group = p2p.currentGroup();
                    if (listener != null) listener.onIdChanged(newCode);
                } else {
                    group = p2p.joinGroup(code);             // 加入既有 group
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
                        HistoryManager.getInstance().addHistory(event.from(), event.message(), true, "TEXT");
                        if (listener != null) {
                            listener.onMessageReceived(event.from(), event.message());
                        }
                    }
                    case FILE_OFFER -> {
                        latestOfferByPeer.put(event.from(), event.offerId());
                        if (listener != null) {
                            listener.onFileOffer(event.from(), event.fileName(), event.fileSize());
                        }
                    }
                    case FILE_SAVED -> {
                        Path file = event.file();
                        if (file != null && listener != null) {
                            HistoryManager.getInstance().addHistory(event.from(), file.toString(), true, "FILE");
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
        if (group == null) {
            System.err.println("錯誤：P2P 群組尚未連線，無法傳送檔案。");
            return;
        }
        
        // 使用原本的 API 傳送檔案
        try {
            group.send(null,file.toPath(), peerName);
            group.send("Hello Jerry", null, "jerry");
            System.out.println("已呼叫 API 傳送檔案給: " + peerName);
        } catch (Exception ex) {
            System.err.println("傳送檔案時發生異常: " + ex.getMessage());
            ex.printStackTrace();
        }

        // if (group == null) return;
        // try {
        //     group.send(null, file.toPath(), peerName);
        // } catch (Exception ex) {
        //     ex.printStackTrace();
        // }
    }

    @Override
    public void requestDownload(String peerName) {
        String offerId = latestOfferByPeer.get(peerName);
        if (offerId == null || group == null) {
            System.err.println("[P2P] 找不到 " + peerName + " 的待下載 offer");
            return;
        }
        try {
            group.save(offerId);    // 後端背景下載，完成時觸發 FILE_SAVED
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}