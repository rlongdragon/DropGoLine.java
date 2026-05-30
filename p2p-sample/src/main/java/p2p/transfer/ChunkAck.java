package p2p.transfer;

import java.util.UUID;

public record ChunkAck(
        UUID fileId,
        int chunkIndex,
        boolean accepted,
        String message
) {
}
