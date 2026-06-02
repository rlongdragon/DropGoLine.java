package p2p.session;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import p2p.transfer.FileChecksums;

public class P2pSession implements AutoCloseable {
    private static final long KEEPALIVE_SECONDS = 10;
    private static final Listener NOOP_LISTENER = new Listener() {
    };

    private final String localPeerId;
    private final String remotePeerId;
    private final Path downloadDirectory;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicBoolean closedOnce = new AtomicBoolean();
    private final Runnable onClose;
    private final Listener listener;
    private final Map<String, LocalOffer> localOffers = new ConcurrentHashMap<>();
    private final Map<String, RemoteOffer> remoteOffers = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public P2pSession(String localPeerId, InputStream input, OutputStream output, Path downloadDirectory) {
        this(localPeerId, "peer", input, output, downloadDirectory);
    }

    public P2pSession(String localPeerId, String remotePeerId, InputStream input, OutputStream output,
                       Path downloadDirectory) {
        this(localPeerId, remotePeerId, input, output, downloadDirectory, () -> {
        });
    }

    public P2pSession(String localPeerId, String remotePeerId, InputStream input, OutputStream output,
                       Path downloadDirectory, Runnable onClose) {
        this(localPeerId, remotePeerId, input, output, downloadDirectory, onClose, NOOP_LISTENER);
    }

    public P2pSession(String localPeerId, String remotePeerId, InputStream input, OutputStream output,
                       Path downloadDirectory, Runnable onClose, Listener listener) {
        this.localPeerId = localPeerId;
        this.remotePeerId = remotePeerId == null || remotePeerId.isBlank() ? "peer" : remotePeerId;
        this.input = new DataInputStream(input);
        this.output = new DataOutputStream(output);
        this.downloadDirectory = downloadDirectory;
        this.onClose = onClose == null ? () -> {
        } : onClose;
        this.listener = listener == null ? NOOP_LISTENER : listener;
    }

    public void startReader() {
        Thread reader = new Thread(this::readLoop, "chat-reader-" + localPeerId);
        reader.setDaemon(true);
        reader.start();
        Thread keepalive = new Thread(this::keepaliveLoop, "chat-keepalive-" + localPeerId + "-" + remotePeerId);
        keepalive.setDaemon(true);
        keepalive.start();
    }

    public void sendHello() throws IOException {
        synchronized (output) {
            output.writeUTF(SessionProtocol.MAGIC);
            output.flush();
        }
    }

    public void expectHello() throws IOException {
        String magic = input.readUTF();
        if (!SessionProtocol.MAGIC.equals(magic)) {
            throw new IOException("unsupported chat protocol: " + magic);
        }
    }

    public void sendText(String text) throws IOException {
        synchronized (output) {
            output.writeInt(SessionProtocol.TYPE_TEXT);
            output.writeUTF(text);
            output.flush();
        }
    }

    public void offerFile(Path path) throws IOException {
        System.out.println("[DropGoLine][DirectSession] offerFile requested local=" + localPeerId
                + ", remote=" + remotePeerId + ", path=" + path);
        if (!Files.isRegularFile(path)) {
            throw new IOException("file does not exist: " + path);
        }

        String id = UUID.randomUUID().toString().substring(0, 8);
        long size = Files.size(path);
        String checksum = FileChecksums.sha256(path);
        localOffers.put(id, new LocalOffer(path, path.getFileName().toString(), size, checksum));
        System.out.println("[DropGoLine][DirectSession] sending file offer id=" + id
                + ", remote=" + remotePeerId + ", file=" + path.getFileName()
                + ", size=" + size);
        synchronized (output) {
            output.writeInt(SessionProtocol.TYPE_FILE_OFFER);
            output.writeUTF(id);
            output.writeUTF(path.getFileName().toString());
            output.writeLong(size);
            output.writeUTF(checksum);
            output.flush();
        }
        listener.onNotice(localPeerId, "offered " + path + " as " + id);
    }

    public void requestFile(String id) throws IOException {
        RemoteOffer offer = remoteOffers.get(id);
        long offset = resumeOffset(offer);
        System.out.println("[DropGoLine][DirectSession] requestFile id=" + id
                + ", local=" + localPeerId + ", remote=" + remotePeerId);
        synchronized (output) {
            output.writeInt(SessionProtocol.TYPE_FILE_REQUEST);
            output.writeUTF(id);
            output.writeLong(offset);
            output.flush();
        }
        if (offer == null) {
            listener.onNotice(localPeerId, "requested " + id);
        } else if (offset > 0) {
            listener.onNotice(localPeerId, "requested " + id + " (" + offer.fileName() + ") from byte " + offset);
        } else {
            listener.onNotice(localPeerId, "requested " + id + " (" + offer.fileName() + ")");
        }
    }

    public void waitUntilClosed() throws InterruptedException {
        closed.await();
    }

    @Override
    public void close() {
        if (!closedOnce.compareAndSet(false, true)) {
            return;
        }
        running = false;
        closeQuietly(input);
        closeQuietly(output);
        closed.countDown();
        onClose.run();
    }

    private void readLoop() {
        try {
            while (running) {
                int type = input.readInt();
                switch (type) {
                    case SessionProtocol.TYPE_TEXT -> listener.onText(remotePeerId, input.readUTF());
                    case SessionProtocol.TYPE_FILE_OFFER -> receiveFileOffer();
                    case SessionProtocol.TYPE_FILE_REQUEST -> sendRequestedFile(input.readUTF(), input.readLong());
                    case SessionProtocol.TYPE_FILE_START -> receiveFile();
                    case SessionProtocol.TYPE_NOTICE -> listener.onNotice(remotePeerId, input.readUTF());
                    case SessionProtocol.TYPE_KEEPALIVE -> {
                    }
                    default -> throw new IOException("unknown chat frame type: " + type);
                }
            }
        } catch (EOFException ignored) {
            listener.onDisconnected(remotePeerId);
        } catch (SocketException e) {
            if (running) {
                listener.onDisconnected(remotePeerId);
            }
        } catch (Exception e) {
            if (running) {
                if (isClosedConnection(e)) {
                    listener.onDisconnected(remotePeerId);
                } else {
                    listener.onError(remotePeerId, e);
                }
            }
        } finally {
            close();
        }
    }

    private void keepaliveLoop() {
        while (running) {
            try {
                if (closed.await(KEEPALIVE_SECONDS, TimeUnit.SECONDS)) {
                    return;
                }
                sendKeepalive();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (running) {
                    close();
                }
                return;
            }
        }
    }

    private void receiveFileOffer() throws IOException {
        String id = input.readUTF();
        String fileName = sanitizeFileName(input.readUTF());
        long size = input.readLong();
        String checksum = input.readUTF();
        System.out.println("[DropGoLine][DirectSession] received file offer id=" + id
                + ", from=" + remotePeerId + ", file=" + fileName + ", size=" + size);
        remoteOffers.put(id, new RemoteOffer(fileName, size, checksum));
        listener.onFileOffer(remotePeerId, id, fileName, size);
    }

    private void sendRequestedFile(String id, long offset) throws IOException {
        System.out.println("[DropGoLine][DirectSession] received file request id=" + id
                + ", from=" + remotePeerId + ", offset=" + offset);
        // Security boundary: the peer may request only an opaque offer id.
        // It never supplies a path, and we only serve files explicitly shared
        // earlier by this local process through /file.
        LocalOffer offer = localOffers.get(id);
        if (offer == null) {
            System.out.println("[DropGoLine][DirectSession] file request missing local offer id=" + id);
            sendNotice("file offer not found: " + id);
            return;
        }
        sendFile(id, offer, offset);
    }

    private void sendFile(String id, LocalOffer offer, long offset) throws IOException {
        if (Files.size(offer.path()) != offer.size()
                || !FileChecksums.sha256(offer.path()).equalsIgnoreCase(offer.checksum())) {
            sendNotice("file changed after offer; share it again: " + id);
            return;
        }
        if (offset < 0 || offset > offer.size()) {
            sendNotice("invalid resume offset for " + id + ": " + offset);
            return;
        }
        System.out.println("[DropGoLine][DirectSession] sendFile start id=" + id
                + ", remote=" + remotePeerId + ", file=" + offer.path()
                + ", size=" + offer.size() + ", offset=" + offset);
        synchronized (output) {
            output.writeInt(SessionProtocol.TYPE_FILE_START);
            output.writeUTF(id);
            output.writeUTF(offer.fileName());
            output.writeLong(offer.size());
            output.writeUTF(offer.checksum());
            output.writeLong(offset);

            byte[] buffer = new byte[SessionProtocol.CHUNK_SIZE];
            try (InputStream fileInput = Files.newInputStream(offer.path())) {
                skipFully(fileInput, offset);
                int read;
                int chunkIndex = 0;
                long sent = 0;
                while ((read = fileInput.read(buffer)) >= 0) {
                    output.writeInt(SessionProtocol.TYPE_FILE_CHUNK);
                    output.writeUTF(id);
                    output.writeInt(read);
                    output.write(buffer, 0, read);
                    sent += read;
                    System.out.println("[DropGoLine][DirectSession] sendFile chunk id=" + id
                            + ", chunk=" + chunkIndex + ", bytes=" + read
                            + ", sent=" + (offset + sent) + "/" + offer.size());
                    chunkIndex++;
                }
            }

            output.writeInt(SessionProtocol.TYPE_FILE_END);
            output.writeUTF(id);
            output.flush();
        }
        System.out.println("[DropGoLine][DirectSession] sendFile complete id=" + id
                + ", remote=" + remotePeerId + ", file=" + offer.path());
        listener.onNotice(localPeerId, "sent " + offer.path() + " for " + id
                + (offset > 0 ? " from byte " + offset : ""));
    }

    private void receiveFile() throws IOException {
        String id = input.readUTF();
        String fileName = sanitizeFileName(input.readUTF());
        long size = input.readLong();
        String checksum = input.readUTF();
        long offset = input.readLong();
        Files.createDirectories(downloadDirectory);
        Path target = downloadDirectory.resolve(fileName);
        Path partial = partialPath(target);
        if (offset < 0 || offset > size) {
            throw new IOException("invalid file resume offset for " + id + ": " + offset);
        }
        long existing = Files.isRegularFile(partial) ? Files.size(partial) : 0;
        if (offset > 0 && existing != offset) {
            throw new IOException("file resume offset mismatch for " + id + ": expected local partial "
                    + existing + ", got " + offset);
        }

        long received = offset;
        try (OutputStream fileOutput = offset > 0
                ? Files.newOutputStream(partial, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                : Files.newOutputStream(partial, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
        System.out.println("[DropGoLine][DirectSession] receiveFile start id=" + id
                + ", from=" + remotePeerId + ", target=" + target
                + ", size=" + size + ", offset=" + offset);

            int chunkIndex = 0;
            while (true) {
                int type = input.readInt();
                if (type == SessionProtocol.TYPE_FILE_END) {
                    String endId = input.readUTF();
                    if (!id.equals(endId)) {
                        throw new IOException("file end id mismatch");
                    }
                    break;
                }
                if (type != SessionProtocol.TYPE_FILE_CHUNK) {
                    throw new IOException("expected file chunk, got frame type " + type);
                }
                String chunkId = input.readUTF();
                if (!id.equals(chunkId)) {
                    throw new IOException("file chunk id mismatch");
                }
                int length = input.readInt();
                if (length < 0 || length > SessionProtocol.CHUNK_SIZE) {
                    throw new IOException("invalid file chunk length: " + length);
                }
                byte[] chunk = new byte[length];
                input.readFully(chunk);
                fileOutput.write(chunk);
                received += length;
                System.out.println("[DropGoLine][DirectSession] receiveFile chunk id=" + id
                        + ", chunk=" + chunkIndex + ", bytes=" + length
                        + ", received=" + received + "/" + size);
                chunkIndex++;
            }
        }

        if (received != size) {
            throw new IOException("file size mismatch for " + id + ": expected " + size + ", got " + received);
        }
        String actualChecksum = FileChecksums.sha256(partial);
        if (!actualChecksum.equalsIgnoreCase(checksum)) {
            throw new IOException("file checksum mismatch for " + id + ": expected "
                    + checksum + ", got " + actualChecksum);
        }
        moveCompletedFile(partial, target);
        remoteOffers.remove(id);
        System.out.println("[DropGoLine][DirectSession] receiveFile complete id=" + id
                + ", from=" + remotePeerId + ", target=" + target
                + ", received=" + received);
        listener.onFileSaved(remotePeerId, id, target);
    }

    private long resumeOffset(RemoteOffer offer) throws IOException {
        if (offer == null) {
            return 0;
        }
        Path partial = partialPath(downloadDirectory.resolve(offer.fileName()));
        if (!Files.isRegularFile(partial)) {
            return 0;
        }
        long offset = Files.size(partial);
        return offset >= offer.size() ? 0 : offset;
    }

    private Path partialPath(Path target) {
        Path fileName = target.getFileName();
        String partialName = (fileName == null ? "download" : fileName.toString()) + ".part";
        Path parent = target.getParent();
        return parent == null ? Path.of(partialName) : parent.resolve(partialName);
    }

    private void moveCompletedFile(Path partial, Path target) throws IOException {
        try {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void skipFully(InputStream input, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() == -1) {
                    throw new IOException("cannot skip to resume offset " + bytes);
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private void sendNotice(String message) throws IOException {
        synchronized (output) {
            output.writeInt(SessionProtocol.TYPE_NOTICE);
            output.writeUTF(message);
            output.flush();
        }
    }

    private void sendKeepalive() throws IOException {
        synchronized (output) {
            output.writeInt(SessionProtocol.TYPE_KEEPALIVE);
            output.flush();
        }
    }

    private String sanitizeFileName(String fileName) {
        String sanitized = Path.of(fileName).getFileName().toString()
                .replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_")
                .trim();
        return sanitized.isBlank() ? "download" : sanitized;
    }

    private boolean isClosedConnection(Exception e) {
        String message = e.getMessage();
        return message != null
                && (message.equalsIgnoreCase("Connection closed")
                || message.equalsIgnoreCase("Socket closed")
                || message.contains("ClosedChannelException"));
    }

    private void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private record RemoteOffer(String fileName, long size, String checksum) {
    }

    private record LocalOffer(Path path, String fileName, long size, String checksum) {
    }

    public interface Listener {
        default void onText(String fromPeerId, String text) {
        }

        default void onFileOffer(String fromPeerId, String id, String fileName, long size) {
        }

        default void onFileSaved(String fromPeerId, String id, Path target) {
        }

        default void onNotice(String fromPeerId, String message) {
        }

        default void onDisconnected(String peerId) {
        }

        default void onError(String peerId, Exception error) {
        }
    }
}
