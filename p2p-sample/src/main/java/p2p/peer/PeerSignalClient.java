package p2p.peer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import p2p.signaling.SignalMessage;

public class PeerSignalClient implements AutoCloseable {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final BlockingQueue<SignalMessage> incoming = new LinkedBlockingQueue<>();
    private final String peerId;
    private final WebSocket webSocket;

    public PeerSignalClient(String signalingUrl, String peerId) throws IOException {
        this.peerId = peerId;
        URI uri = URI.create(signalingUrl + (signalingUrl.contains("?") ? "&" : "?") + "peerId=" + peerId);
        try {
            this.webSocket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(uri, new Listener())
                    .join();
        } catch (CompletionException e) {
            throw new IOException(connectionError(uri, e), e);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid signaling URL: " + uri, e);
        }
    }

    public void send(String type, String to, Object payload) throws Exception {
        SignalMessage message = new SignalMessage(type, peerId, to, objectMapper.valueToTree(payload));
        webSocket.sendText(objectMapper.writeValueAsString(message), true).join();
    }

    public void sendToServer(String type, Object payload) throws Exception {
        send(type, "signal", payload);
    }

    public <T> T waitForPayload(String type, Class<T> payloadType, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            SignalMessage message = incoming.poll(remainingMillis, TimeUnit.MILLISECONDS);
            if (message != null && Objects.equals("error", message.type())) {
                throw new IllegalStateException("signaling error: " + errorText(message));
            }
            if (message != null && Objects.equals(type, message.type())) {
                return objectMapper.treeToValue(message.payload(), payloadType);
            }
        }
        throw new IllegalStateException("timed out waiting for signal type " + type);
    }

    public SignalMessage waitFor(String type, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            SignalMessage message = incoming.poll(remainingMillis, TimeUnit.MILLISECONDS);
            if (message != null && Objects.equals("error", message.type())) {
                throw new IllegalStateException("signaling error: " + errorText(message));
            }
            if (message != null && Objects.equals(type, message.type())) {
                return message;
            }
        }
        throw new IllegalStateException("timed out waiting for signal type " + type);
    }

    public SignalMessage nextSignal(Duration timeout) throws InterruptedException {
        return incoming.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private String errorText(SignalMessage message) {
        if (message.payload() != null) {
            return message.payload().toString();
        }
        return "server rejected signal";
    }

    private String connectionError(URI uri, CompletionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof WebSocketHandshakeException handshake) {
            return "cannot connect to signaling WebSocket " + uri
                    + " (HTTP " + handshake.getResponse().statusCode()
                    + "). Check server IP/port and make sure the Java signaling server is running.";
        }
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return "cannot connect to signaling WebSocket " + uri + ": " + cause.getMessage();
        }
        return "cannot connect to signaling WebSocket " + uri;
    }

    @Override
    public void close() {
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String json = buffer.toString();
                buffer.setLength(0);
                try {
                    SignalMessage message = objectMapper.readValue(json, SignalMessage.class);
                    if (!"registered".equals(message.type())) {
                        incoming.add(message);
                    }
                } catch (Exception e) {
                    incoming.add(new SignalMessage("error", "signal", peerId, objectMapper.valueToTree(e.getMessage())));
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }
    }
}
