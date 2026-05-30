package p2p.transfer;

import java.util.UUID;

public record FileChunk(
        UUID fileId,
        long totalSize,
        int chunkIndex,
        int chunkTotal,
        String checksumSha256,
        byte[] payload
) {
}
