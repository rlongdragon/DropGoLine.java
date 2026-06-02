package p2p.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P2pSessionTest {
    @TempDir
    private Path tempDir;

    @Test
    void directFileRequestResumesFromExistingPartialAndVerifiesChecksum() throws Exception {
        byte[] content = "resume this direct file from byte offset\n".getBytes(StandardCharsets.UTF_8);
        Path source = tempDir.resolve("source.txt");
        Files.write(source, content);
        Path aliceDownloads = tempDir.resolve("alice");
        Files.createDirectories(aliceDownloads);

        SessionPair pair = SessionPair.open(aliceDownloads, tempDir.resolve("bob"));
        CountDownLatch offerSeen = new CountDownLatch(1);
        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch resumedNotice = new CountDownLatch(1);
        AtomicReference<String> offerId = new AtomicReference<>();
        AtomicReference<Path> savedFile = new AtomicReference<>();

        pair.alice().listener.onFileOffer = (from, id, fileName, size) -> {
            offerId.set(id);
            offerSeen.countDown();
        };
        pair.alice().listener.onFileSaved = (from, id, target) -> {
            savedFile.set(target);
            saved.countDown();
        };
        pair.bob().listener.onNotice = (from, message) -> {
            if (message.contains("from byte 11")) {
                resumedNotice.countDown();
            }
        };

        try (pair) {
            pair.start();
            pair.bob().session.offerFile(source);
            assertTrue(offerSeen.await(5, TimeUnit.SECONDS), "offer was not delivered");

            Files.write(aliceDownloads.resolve(source.getFileName() + ".part"),
                    java.util.Arrays.copyOf(content, 11));
            pair.alice().session.requestFile(offerId.get());

            assertTrue(saved.await(5, TimeUnit.SECONDS), "resumed file was not saved");
            assertTrue(resumedNotice.await(5, TimeUnit.SECONDS), "sender did not resume from partial offset");
            assertArrayEquals(content, Files.readAllBytes(savedFile.get()));
            assertTrue(Files.notExists(aliceDownloads.resolve(source.getFileName() + ".part")));
        }
    }

    @Test
    void directFileRequestRejectsFileChangedAfterOffer() throws Exception {
        Path source = tempDir.resolve("changed.txt");
        Files.writeString(source, "original content");

        SessionPair pair = SessionPair.open(tempDir.resolve("alice"), tempDir.resolve("bob"));
        CountDownLatch offerSeen = new CountDownLatch(1);
        CountDownLatch changedNotice = new CountDownLatch(1);
        AtomicReference<String> offerId = new AtomicReference<>();

        pair.alice().listener.onFileOffer = (from, id, fileName, size) -> {
            offerId.set(id);
            offerSeen.countDown();
        };
        pair.alice().listener.onNotice = (from, message) -> {
            if (message.contains("file changed after offer")) {
                changedNotice.countDown();
            }
        };

        try (pair) {
            pair.start();
            pair.bob().session.offerFile(source);
            assertTrue(offerSeen.await(5, TimeUnit.SECONDS), "offer was not delivered");

            Files.writeString(source, "mutated content");
            pair.alice().session.requestFile(offerId.get());

            assertTrue(changedNotice.await(5, TimeUnit.SECONDS), "changed file was not rejected");
        }
    }

    @Test
    void directFileRequestRejectsInvalidResumeOffsetWithoutCrashing() throws Exception {
        Path source = tempDir.resolve("small.txt");
        Files.writeString(source, "small");

        SessionPair pair = SessionPair.open(tempDir.resolve("alice"), tempDir.resolve("bob"));
        CountDownLatch offerSeen = new CountDownLatch(1);
        CountDownLatch invalidOffsetNotice = new CountDownLatch(1);
        AtomicReference<String> offerId = new AtomicReference<>();

        pair.alice().listener.onFileOffer = (from, id, fileName, size) -> {
            offerId.set(id);
            offerSeen.countDown();
        };
        pair.alice().listener.onNotice = (from, message) -> {
            if (message.contains("invalid resume offset")) {
                invalidOffsetNotice.countDown();
            }
        };

        try (pair) {
            pair.start();
            pair.bob().session.offerFile(source);
            assertTrue(offerSeen.await(5, TimeUnit.SECONDS), "offer was not delivered");

            synchronized (pair.aliceToBob()) {
                pair.aliceToBob().writeInt(SessionProtocol.TYPE_FILE_REQUEST);
                pair.aliceToBob().writeUTF(offerId.get());
                pair.aliceToBob().writeLong(Files.size(source) + 10);
                pair.aliceToBob().flush();
            }

            assertTrue(invalidOffsetNotice.await(5, TimeUnit.SECONDS), "invalid offset was not rejected");
        }
    }

    private record SessionPair(Endpoint alice, Endpoint bob, DataOutputStream aliceToBob) implements AutoCloseable {
        static SessionPair open(Path aliceDownloads, Path bobDownloads) throws Exception {
            PipedInputStream aliceInput = new PipedInputStream(128 * 1024);
            PipedOutputStream bobOutput = new PipedOutputStream(aliceInput);
            PipedInputStream bobInput = new PipedInputStream(128 * 1024);
            PipedOutputStream aliceOutput = new PipedOutputStream(bobInput);

            TestListener aliceListener = new TestListener();
            TestListener bobListener = new TestListener();
            P2pSession alice = new P2pSession("alice", "bob", aliceInput, aliceOutput, aliceDownloads,
                    () -> {
                    }, aliceListener);
            P2pSession bob = new P2pSession("bob", "alice", bobInput, bobOutput, bobDownloads,
                    () -> {
                    }, bobListener);
            return new SessionPair(new Endpoint(alice, aliceListener), new Endpoint(bob, bobListener),
                    new DataOutputStream(aliceOutput));
        }

        void start() {
            alice.session.startReader();
            bob.session.startReader();
        }

        @Override
        public void close() {
            alice.session.close();
            bob.session.close();
        }
    }

    private record Endpoint(P2pSession session, TestListener listener) {
    }

    private static final class TestListener implements P2pSession.Listener {
        private TextHandler onNotice = (from, message) -> {
        };
        private FileOfferHandler onFileOffer = (from, id, fileName, size) -> {
        };
        private FileSavedHandler onFileSaved = (from, id, target) -> {
        };

        @Override
        public void onNotice(String fromPeerId, String message) {
            onNotice.handle(fromPeerId, message);
        }

        @Override
        public void onFileOffer(String fromPeerId, String id, String fileName, long size) {
            onFileOffer.handle(fromPeerId, id, fileName, size);
        }

        @Override
        public void onFileSaved(String fromPeerId, String id, Path target) {
            onFileSaved.handle(fromPeerId, id, target);
        }
    }

    private interface TextHandler {
        void handle(String from, String message);
    }

    private interface FileOfferHandler {
        void handle(String from, String id, String fileName, long size);
    }

    private interface FileSavedHandler {
        void handle(String from, String id, Path target);
    }
}
