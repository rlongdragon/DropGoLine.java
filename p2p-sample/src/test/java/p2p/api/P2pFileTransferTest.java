package p2p.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import p2p.P2pApplication;

@SpringBootTest(classes = P2pApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class P2pFileTransferTest {
    @LocalServerPort
    private int port;

    @TempDir
    private Path tempDir;

    @Test
    void transfersSharedFileThroughExistingApi() throws Exception {
        Path aliceDownloads = tempDir.resolve("alice-downloads");
        Path bobDownloads = tempDir.resolve("bob-downloads");
        Path source = tempDir.resolve("source.txt");
        byte[] content = "file transfer integration test\n".getBytes();
        Files.write(source, content);

        CountDownLatch aliceSawOffer = new CountDownLatch(1);
        CountDownLatch aliceSavedFile = new CountDownLatch(1);
        AtomicReference<String> offerId = new AtomicReference<>();
        AtomicReference<Path> savedFile = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();

        String signalingUrl = "ws://127.0.0.1:" + port + "/signal";
        try (P2p alice = P2p.connect("alice-file-test", signalingUrl, aliceDownloads);
             P2p bob = P2p.connect("bob-file-test", signalingUrl, bobDownloads)) {
            String groupId = alice.createGroup();
            P2pSessionInstance aliceGroup = alice.currentGroup();
            aliceGroup.onReceived(event -> {
                if (event.type() == P2pEvent.Type.FILE_OFFER && "bob-file-test".equals(event.from())) {
                    offerId.set(event.offerId());
                    aliceSawOffer.countDown();
                }
                if (event.type() == P2pEvent.Type.FILE_SAVED && "bob-file-test".equals(event.from())) {
                    savedFile.set(event.file());
                    aliceSavedFile.countDown();
                }
            }, (peerId, reason) -> error.compareAndSet(null, peerId + ": " + reason));

            P2pSessionInstance bobGroup = bob.joinGroup(groupId);
            bobGroup.onReceived(event -> {
            }, (peerId, reason) -> error.compareAndSet(null, peerId + ": " + reason));

            bobGroup.send(null, source, "alice-file-test");

            assertTrue(aliceSawOffer.await(20, TimeUnit.SECONDS), "Alice did not receive Bob's file offer");
            assertNotNull(offerId.get(), "file offer id was not captured");
            Files.createDirectories(aliceDownloads);
            Files.write(aliceDownloads.resolve(source.getFileName().toString() + ".part"),
                    java.util.Arrays.copyOf(content, 7));
            aliceGroup.save(offerId.get());

            assertTrue(aliceSavedFile.await(30, TimeUnit.SECONDS), "Alice did not save Bob's file");
            assertArrayEquals(content, Files.readAllBytes(savedFile.get()));
            assertTrue(Files.notExists(aliceDownloads.resolve(source.getFileName().toString() + ".part")));
            assertTrue(error.get() == null || error.get().contains("using relay fallback"),
                    "unexpected async error: " + error.get());
        }
    }

    @Test
    void rejectsCorruptPartialResumeWithChecksumMismatch() throws Exception {
        Path aliceDownloads = tempDir.resolve("alice-corrupt-downloads");
        Path bobDownloads = tempDir.resolve("bob-corrupt-downloads");
        Path source = tempDir.resolve("corrupt-source.txt");
        byte[] content = "checksum must reject a corrupt partial resume\n".getBytes();
        Files.write(source, content);

        CountDownLatch aliceSawOffer = new CountDownLatch(1);
        CountDownLatch aliceSavedFile = new CountDownLatch(1);
        CountDownLatch checksumMismatch = new CountDownLatch(1);
        AtomicReference<String> offerId = new AtomicReference<>();

        String signalingUrl = "ws://127.0.0.1:" + port + "/signal";
        try (P2p alice = P2p.connect("alice-corrupt-test", signalingUrl, aliceDownloads);
             P2p bob = P2p.connect("bob-corrupt-test", signalingUrl, bobDownloads)) {
            String groupId = alice.createGroup();
            P2pSessionInstance aliceGroup = alice.currentGroup();
            aliceGroup.onReceived(event -> {
                if (event.type() == P2pEvent.Type.FILE_OFFER && "bob-corrupt-test".equals(event.from())) {
                    offerId.set(event.offerId());
                    aliceSawOffer.countDown();
                }
                if (event.type() == P2pEvent.Type.FILE_SAVED && "bob-corrupt-test".equals(event.from())) {
                    aliceSavedFile.countDown();
                }
            }, (peerId, reason) -> {
                if (reason.contains("checksum mismatch")) {
                    checksumMismatch.countDown();
                }
            });

            P2pSessionInstance bobGroup = bob.joinGroup(groupId);
            bobGroup.onReceived(event -> {
            });

            bobGroup.send(null, source, "alice-corrupt-test");

            assertTrue(aliceSawOffer.await(20, TimeUnit.SECONDS), "Alice did not receive Bob's file offer");
            Files.createDirectories(aliceDownloads);
            Files.writeString(aliceDownloads.resolve(source.getFileName().toString() + ".part"), "BADPREFIX");
            aliceGroup.save(offerId.get());

            assertTrue(checksumMismatch.await(30, TimeUnit.SECONDS), "corrupt partial was not rejected");
            assertFalse(aliceSavedFile.await(1, TimeUnit.SECONDS), "corrupt file should not be saved");
            assertTrue(Files.exists(aliceDownloads.resolve(source.getFileName().toString() + ".part")),
                    "corrupt partial should remain for inspection/retry");
        }
    }

    @Test
    void rejectsSourceFileChangedAfterOfferBeforeTransferStarts() throws Exception {
        Path aliceDownloads = tempDir.resolve("alice-changed-downloads");
        Path bobDownloads = tempDir.resolve("bob-changed-downloads");
        Path source = tempDir.resolve("changed-after-offer.txt");
        Files.writeString(source, "original shared content");

        CountDownLatch aliceSawOffer = new CountDownLatch(1);
        CountDownLatch changedNotice = new CountDownLatch(1);
        CountDownLatch aliceSavedFile = new CountDownLatch(1);
        AtomicReference<String> offerId = new AtomicReference<>();

        String signalingUrl = "ws://127.0.0.1:" + port + "/signal";
        try (P2p alice = P2p.connect("alice-changed-test", signalingUrl, aliceDownloads);
             P2p bob = P2p.connect("bob-changed-test", signalingUrl, bobDownloads)) {
            String groupId = alice.createGroup();
            P2pSessionInstance aliceGroup = alice.currentGroup();
            aliceGroup.onReceived(event -> {
                if (event.type() == P2pEvent.Type.FILE_OFFER && "bob-changed-test".equals(event.from())) {
                    offerId.set(event.offerId());
                    aliceSawOffer.countDown();
                }
                if (event.type() == P2pEvent.Type.NOTICE
                        && event.message() != null
                        && event.message().contains("file changed after offer")) {
                    changedNotice.countDown();
                }
                if (event.type() == P2pEvent.Type.FILE_SAVED && "bob-changed-test".equals(event.from())) {
                    aliceSavedFile.countDown();
                }
            });

            P2pSessionInstance bobGroup = bob.joinGroup(groupId);
            bobGroup.send(null, source, "alice-changed-test");

            assertTrue(aliceSawOffer.await(20, TimeUnit.SECONDS), "Alice did not receive Bob's file offer");
            Files.writeString(source, "mutated after offer");
            aliceGroup.save(offerId.get());

            assertTrue(changedNotice.await(30, TimeUnit.SECONDS), "changed source file was not rejected");
            assertFalse(aliceSavedFile.await(1, TimeUnit.SECONDS), "changed source should not be saved");
        }
    }

    @Test
    @Disabled("Diagnostic only: direct ICE path is not reliable in the current test environment yet.")
    void transfersFileOverDirectConnectionAfterP2pSessionIsEstablished() throws Exception {
        Path aliceDownloads = tempDir.resolve("alice-direct-downloads");
        Path bobDownloads = tempDir.resolve("bob-direct-downloads");
        Path source = tempDir.resolve("direct-source.txt");
        byte[] content = "direct file transfer integration test\n".getBytes();
        Files.write(source, content);

        CountDownLatch aliceGotDirectProbe = new CountDownLatch(1);
        CountDownLatch aliceSawDirectOffer = new CountDownLatch(1);
        CountDownLatch aliceSavedDirectFile = new CountDownLatch(1);
        AtomicReference<String> offerId = new AtomicReference<>();
        AtomicReference<Path> savedFile = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();

        String signalingUrl = "ws://127.0.0.1:" + port + "/signal";
        try (P2p alice = P2p.connect("alice-direct-file-test", signalingUrl, aliceDownloads);
             P2p bob = P2p.connect("bob-direct-file-test", signalingUrl, bobDownloads)) {
            String groupId = alice.createGroup();
            P2pSessionInstance aliceGroup = alice.currentGroup();
            aliceGroup.onReceived(event -> {
                if (event.type() == P2pEvent.Type.MESSAGE
                        && "bob-direct-file-test".equals(event.from())
                        && event.message() != null
                        && event.message().startsWith("direct-ready-")
                        && event.direct()) {
                    aliceGotDirectProbe.countDown();
                }
                if (event.type() == P2pEvent.Type.FILE_OFFER && "bob-direct-file-test".equals(event.from())) {
                    if (event.direct()) {
                        offerId.set(event.offerId());
                        aliceSawDirectOffer.countDown();
                    } else {
                        error.compareAndSet(null, "file offer used relay instead of direct");
                    }
                }
                if (event.type() == P2pEvent.Type.FILE_SAVED && "bob-direct-file-test".equals(event.from())) {
                    if (event.direct()) {
                        savedFile.set(event.file());
                        aliceSavedDirectFile.countDown();
                    } else {
                        error.compareAndSet(null, "file save used relay instead of direct");
                    }
                }
            }, (peerId, reason) -> error.compareAndSet(null, peerId + ": " + reason));

            P2pSessionInstance bobGroup = bob.joinGroup(groupId);
            bobGroup.onReceived(event -> {
            }, (peerId, reason) -> error.compareAndSet(null, peerId + ": " + reason));

            for (int i = 0; i < 60 && aliceGotDirectProbe.getCount() > 0; i++) {
                bobGroup.send("direct-ready-" + i, null, "alice-direct-file-test");
                aliceGotDirectProbe.await(1, TimeUnit.SECONDS);
            }
            assertTrue(aliceGotDirectProbe.await(1, TimeUnit.SECONDS),
                    "direct P2P message was not established before file transfer");

            bobGroup.send(null, source, "alice-direct-file-test");

            assertTrue(aliceSawDirectOffer.await(20, TimeUnit.SECONDS),
                    "Alice did not receive Bob's direct file offer");
            assertNotNull(offerId.get(), "direct file offer id was not captured");
            aliceGroup.save(offerId.get());

            assertTrue(aliceSavedDirectFile.await(30, TimeUnit.SECONDS),
                    "Alice did not save Bob's direct file");
            assertArrayEquals(content, Files.readAllBytes(savedFile.get()));
            assertTrue(error.get() == null, "unexpected async error: " + error.get());
        }
    }
}
