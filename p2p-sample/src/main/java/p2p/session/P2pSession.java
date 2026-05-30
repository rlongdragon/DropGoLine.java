package p2p.session;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final Map<String, Path> localOffers = new ConcurrentHashMap<>();
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
        if (!Files.isRegularFile(path)) {
            throw new IOException("file does not exist: " + path);
        }

        String id = UUID.randomUUID().toString().substring(0, 8);
        localOffers.put(id, path);
        synchronized (output) {
            output.writeInt(SessionProtocol.TYPE_FILE_OFFER);
            output.writeUTF(id);
            output.writeUTF(path.getFileName().toString());
            output.writeLong(Files.size(path));
            output.flush();
        }
        listener.onNotice(localPeerId, "offered " + path + " as " + id);
    }

    public void requestFile(String id) throws IOException {
        synchronized (output) {
            output.writeInt(SessionProtocol.TYPE_FILE_REQUEST);
            output.writeUTF(id);
            output.flush();
        }
        RemoteOffer offer = remoteOffers.get(id);
        if (offer == null) {
            listener.onNotice(localPeerId, "requested " + id);
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
                    case SessionProtocol.TYPE_FILE_REQUEST -> sendRequestedFile(input.readUTF());
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
        remoteOffers.put(id, new RemoteOffer(fileName, size));
        listener.onFileOffer(remotePeerId, id, fileName, size);
    }

    private void sendRequestedFile(String id) throws IOException {
        // Security boundary: the peer may request only an opaque offer id.
        // It never supplies a path, and we only serve files explicitly shared
        // earlier by this local process through /file.
        Path path = localOffers.get(id);
        if (path == null) {
            sendNotice("file offer not found: " + id);
            return;
        }
        sendFile(id, path);
    }

    private void sendFile(String id, Path path) throws IOException {
        long size = Files.size(path);
        synchronized (output) {
            output.writeInt(SessionProtocol.TYPE_FILE_START);
            output.writeUTF(id);
            output.writeUTF(path.getFileName().toString());
            output.writeLong(size);

            byte[] buffer = new byte[SessionProtocol.CHUNK_SIZE];
            try (InputStream fileInput = Files.newInputStream(path)) {
                int read;
                while ((read = fileInput.read(buffer)) >= 0) {
                    output.writeInt(SessionProtocol.TYPE_FILE_CHUNK);
                    output.writeUTF(id);
                    output.writeInt(read);
                    output.write(buffer, 0, read);
                }
            }

            output.writeInt(SessionProtocol.TYPE_FILE_END);
            output.writeUTF(id);
            output.flush();
        }
        listener.onNotice(localPeerId, "sent " + path + " for " + id);
    }

    private void receiveFile() throws IOException {
        String id = input.readUTF();
        String fileName = sanitizeFileName(input.readUTF());
        long size = input.readLong();
        Files.createDirectories(downloadDirectory);
        Path target = downloadDirectory.resolve(fileName);

        long received = 0;
        try (OutputStream fileOutput = Files.newOutputStream(target)) {
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
            }
        }

        if (received != size) {
            throw new IOException("file size mismatch for " + id + ": expected " + size + ", got " + received);
        }
        remoteOffers.remove(id);
        listener.onFileSaved(remotePeerId, id, target);
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

    private record RemoteOffer(String fileName, long size) {
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
