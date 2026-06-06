package p2p.api;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import p2p.peer.PeerSignalClient;
import p2p.signaling.SignalMessage;

public final class P2p implements AutoCloseable {
    private static final Duration SIGNAL_TIMEOUT = Duration.ofMinutes(5);

    private final String peerId;
    private final Path downloadDirectory;
    private final PeerSignalClient signal;
    private volatile P2pSessionInstance currentGroup;

    public P2p(String peerId, String signalingUrl, Path downloadDirectory) throws Exception {
        if (peerId == null || peerId.isBlank()) {
            throw new IllegalArgumentException("peerId is required");
        }
        this.peerId = peerId;
        this.downloadDirectory = downloadDirectory;
        this.signal = new PeerSignalClient(signalingUrl, peerId);
    }

    public static P2p connect(String peerId, String signalingUrl, Path downloadDirectory) throws Exception {
        return new P2p(peerId, signalingUrl, downloadDirectory);
    }

    public String createGroup() throws Exception {
        return createGroup("auto");
    }

    public String createGroup(String groupId) throws Exception {
        closeCurrentGroup();
        signal.sendToServer("create-group", Map.of("groupId", groupId == null || groupId.isBlank() ? "auto" : groupId));
        SignalMessage created = signal.waitFor("group-created", SIGNAL_TIMEOUT);
        String actualGroupId = created.payload().path("groupId").asText(groupId);
        P2pSessionInstance nextGroup = new P2pSessionInstance(signal, peerId, actualGroupId, downloadDirectory);
        currentGroup = nextGroup;
        nextGroup.start();
        return actualGroupId;
    }

    public P2pSessionInstance joinGroup(String groupId) throws Exception {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId is required");
        }
        System.out.println("[DropGoLine][P2p] joinGroup start groupId=" + groupId + ", peerId=" + peerId);
        closeCurrentGroup();
        System.out.println("[DropGoLine][P2p] joinGroup closeCurrentGroup done, sending join-group");
        signal.sendToServer("join-group", Map.of("groupId", groupId));
        System.out.println("[DropGoLine][P2p] joinGroup sent, waiting for group-joined...");
        SignalMessage joined = signal.waitFor("group-joined", SIGNAL_TIMEOUT);
        System.out.println("[DropGoLine][P2p] joinGroup got group-joined payload=" + joined.payload());
        String actualGroupId = joined.payload().path("groupId").asText(groupId);
        P2pSessionInstance nextGroup = new P2pSessionInstance(signal, peerId, actualGroupId, downloadDirectory);
        currentGroup = nextGroup;
        nextGroup.start(P2pSessionInstance.peersFrom(joined.payload().path("peers")));
        System.out.println("[DropGoLine][P2p] joinGroup complete actualGroupId=" + actualGroupId);
        return currentGroup;
    }

    private void closeCurrentGroup() {
        P2pSessionInstance previous = currentGroup;
        currentGroup = null;
        if (previous != null) {
            previous.close();
        }
    }

    public P2pSessionInstance currentGroup() {
        return currentGroup;
    }

    @Deprecated
    public String createRoom() throws Exception {
        return createGroup();
    }

    @Deprecated
    public String createRoom(String roomId) throws Exception {
        return createGroup(roomId);
    }

    @Deprecated
    public P2pSessionInstance joinRoom(String roomId) throws Exception {
        return joinGroup(roomId);
    }

    @Deprecated
    public P2pSessionInstance currentRoom() {
        return currentGroup();
    }

    @Override
    public void close() {
        P2pSessionInstance group = currentGroup;
        if (group != null) {
            group.close();
        }
        signal.close();
    }
}
