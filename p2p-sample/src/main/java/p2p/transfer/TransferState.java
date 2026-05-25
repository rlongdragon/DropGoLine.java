package p2p.transfer;

import java.util.BitSet;
import java.util.UUID;

public class TransferState {
    private final UUID fileId;
    private final int chunkTotal;
    private final BitSet acknowledgedChunks;

    public TransferState(UUID fileId, int chunkTotal) {
        this.fileId = fileId;
        this.chunkTotal = chunkTotal;
        this.acknowledgedChunks = new BitSet(chunkTotal);
    }

    public UUID fileId() {
        return fileId;
    }

    public synchronized void acknowledge(int chunkIndex) {
        acknowledgedChunks.set(chunkIndex);
    }

    public synchronized boolean isAcknowledged(int chunkIndex) {
        return acknowledgedChunks.get(chunkIndex);
    }

    public synchronized boolean isComplete() {
        return acknowledgedChunks.cardinality() == chunkTotal;
    }

    public synchronized BitSet snapshot() {
        return (BitSet) acknowledgedChunks.clone();
    }
}
