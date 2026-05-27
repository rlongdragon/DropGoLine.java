package p2p.api;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class P2pApiSmokeTest {
    private P2pApiSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        String signalingUrl = args.length == 0 ? "ws://127.0.0.1:18080/signal" : args[0];
        CountDownLatch bobGotAlice = new CountDownLatch(1);
        CountDownLatch bobGotDirect = new CountDownLatch(1);
        CountDownLatch aliceGotBob = new CountDownLatch(1);
        AtomicReference<String> asyncError = new AtomicReference<>();

        try (P2p alice = P2p.connect("alice-smoke", signalingUrl, Path.of("target/smoke/alice"));
             P2p bob = P2p.connect("bob-smoke", signalingUrl, Path.of("target/smoke/bob"))) {
            String groupId = alice.createGroup();
            P2pSessionInstance aliceGroup = alice.currentGroup();
            aliceGroup.createReceivedListener(event -> {
                if (event.type() == P2pEvent.Type.MESSAGE
                        && "bob-smoke".equals(event.from())
                        && "hello alice".equals(event.message())) {
                    aliceGotBob.countDown();
                }
            }, (peerId, reason) -> asyncError.compareAndSet(null, "P2P error for " + peerId + ": " + reason));

            P2pSessionInstance bobGroup = bob.joinGroup(groupId);
            bobGroup.createReceivedListener(event -> {
                if (event.type() == P2pEvent.Type.MESSAGE
                        && "alice-smoke".equals(event.from())
                        && "hello bob".equals(event.message())) {
                    bobGotAlice.countDown();
                }
                if (event.type() == P2pEvent.Type.MESSAGE
                        && "alice-smoke".equals(event.from())
                        && event.message().startsWith("direct probe ")
                        && event.direct()) {
                    bobGotDirect.countDown();
                }
            }, (peerId, reason) -> asyncError.compareAndSet(null, "P2P error for " + peerId + ": " + reason));

            if (!bobGroup.showMembers().contains("alice-smoke")) {
                throw new IllegalStateException("bob does not see alice in group members");
            }

            aliceGroup.send("hello bob");
            bobGroup.send("hello alice", null, "alice-smoke");

            if (!bobGotAlice.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("bob did not receive alice message");
            }
            if (!aliceGotBob.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("alice did not receive bob private message");
            }
            for (int i = 0; i < 60 && bobGotDirect.getCount() > 0; i++) {
                aliceGroup.send("direct probe " + i);
                bobGotDirect.await(1, TimeUnit.SECONDS);
            }
            if (!bobGotDirect.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("bob did not receive a direct P2P message");
            }
            if (asyncError.get() != null) {
                throw new IllegalStateException(asyncError.get());
            }
        }

        System.out.println("P2p API smoke test passed");
        System.exit(0);
    }
}
