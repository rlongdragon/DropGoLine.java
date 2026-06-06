package p2p.peer;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

class PeerSignalClientTest {

    @Test
    void signalingUriEncodesPeerIdQueryParameter() {
        URI uri = PeerSignalClient.signalingUri("ws://127.0.0.1:18080/signal", "User#1138");

        assertThat(uri.toString()).isEqualTo("ws://127.0.0.1:18080/signal?peerId=User%231138");
        assertThat(uri.getFragment()).isNull();
    }

    @Test
    void signalingUriAppendsPeerIdToExistingQuery() {
        URI uri = PeerSignalClient.signalingUri("ws://127.0.0.1:18080/signal?room=abc", "User#1138");

        assertThat(uri.toString()).isEqualTo("ws://127.0.0.1:18080/signal?room=abc&peerId=User%231138");
        assertThat(uri.getFragment()).isNull();
    }
}
