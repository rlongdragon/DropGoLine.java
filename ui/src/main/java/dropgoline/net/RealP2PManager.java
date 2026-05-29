package dropgoline.net;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import p2p.api.P2p;
import p2p.api.P2pSessionInstance;

public class RealP2PManager implements P2PManager{
    private final String localName;
    private final String signallingUrl;
    private final Path downloadDir;

    private P2p p2p;
    private P2pSessionInstance group;
    private P2PListener listener;

    private final Map<String, String> latestOfferByPeer= new HashMap<>();
    private final Set<String> knownMembers = new HashSet<>();
    private ScheduledExecutorService memberMonitor;

    public RealP2PManager(String localName, String signallingUrl, Path downloadDir) {
        this.localName = localName;
        this.signallingUrl = signallingUrl;
        this.downloadDir = downloadDir;
    }

    @Override
    public void setListener(P2PListener listener) {
        this.listener = listener;
    }

    @Override
    public void connect(String code) {
        new Thread(() -> {
            try {
                Files.createDirectories(downloadDir);

                if (p2p != null) {
                    p2p = P2p.connect(localName, signallingUrl, downloadDir);
                }

                if (code == null || code.isBlank()) {
                    String newCode = p2p.createGroup();
                    group = p2p.createGroup();
                    if (listener != null) {
                        listener.onIdChanged(newCode);
                    }
                } else {
                    group = p2p.joinGroup(code);
                    if (listener != null) {
                        listener.onIdChanged(code);
                    }

                    attachEventListeners();
                    startMemberMonitor();
                }
            } catch (Exception ex) {
                System.err.println("P2P連線失敗：" + ex.getMessage());
                ex.printStackTrace();
            }
        }, "p2p-connect").start();
    }

    private void attachEventListener(){
        group.createReceivedListener(
            event -> {
                switch (event.type()){
                    case MESSAGE -> {
                        if (listener != null) {
                            listener.onMessageReceived(event.from(), event.message());
                        }
                    }
                    case FILE_OFFER -> {
                        latestOfferByPeer.put(event.from(), event.fileName());
                        if (listener != null) {
                            listener.onFileOffer(event.from(), event.fileName(), event.fileSize());
                        }
                    }
                    case FILE_SAVED -> {
                        Path file = event.file();
                        if (file != null && listener != null) {
                            listener.onTransferComplete(event.from(), file.toFile());
                        }
                    }
                }
            },
            (peerId, reason) -> {
                System.err.println("P2P notice [" + peerId + "]: " + reason);
            }
        );
    }

    private void startMemberMonitor(){
        if (memberMonitor != null) {
            return;
        }
        memberMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "p2p-member-monitor");
            t.setDaemon(true);
            return t;
        });
        memberMonitor.scheduleAtFixedRate(this::checkMembers, 0, 1, TimeUnit.SECONDS);
    }

    private void checkMembers(){
        try{
            if (group == null) return;
            Set<String> current = group.showMembers();

            for (String m : current){
                if (!knownMembers.contains(m) && !m.equals(localName)){
                    knownMembers.add(m);
                    if (listener != null) {
                        listener.onPeerJoined(m);
                    }
                }
                Set<String> gone = new HashSet<>(knownMembers);
                gone.removeAll(current);
                for (String g : gone){
                    knownMembers.remove(g);
                    if (listener != null) {
                        listener.onPeerLeft(g);
                    }
                }
            }
        }catch (Exception ex){
        }
    }

    @Override
    public void disconnect() {
        if (memberMonitor != null) {
            memberMonitor.shutdownNow();
            memberMonitor = null;
        }

        for (String peer : new HashSet<>(knownMembers)) {
            if (listener != null) {
                listener.onPeerLeft(peer);
            }
            knownMembers.clear();
            latestOfferByPeer.clear();

            if (p2p != null) {
                try{
                    p2p.close();
                } catch (Exception ex) {
                }
                p2p = null;
                group = null;
            }
        }

        @Override
        public void sendText(String peerName, String text) {
            if (group != null) {
                return;
            }
            try{
                group.send(text, null, peerName);
            }catch (Exception ex){
                ex.addSuppressed(ex);
            }
        }

        @Override
        public void sendFile(String peerName, File file) {
            if (group != null) {
                return;
            }
            try{
                group.send(file, null, peerName);
            }catch (Exception ex){
                ex.addSuppressed(ex);
            }
        }

        @Override
        public void requestDownload(String peerName) {
            String offer = latestOfferByPeer.get(peerName);
            if (offer == null || group == null) {
                System.err.println("[P2P] 找不到 " + peerName + " 的待下載 offer");
            }
            try{
                group.save(offerId);
            }catch (Exception ex){
                ex.printStackTrace();
            }
}
