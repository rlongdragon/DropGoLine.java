package p2p.quic;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface QuicChannel extends Closeable {
    TransferStream openStream() throws IOException;

    interface TransferStream extends Closeable {
        InputStream input();

        OutputStream output();
    }
}
