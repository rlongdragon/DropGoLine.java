package p2p.transfer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class FileTransferProtocol {
    private static final int UUID_BYTES = 16;
    private static final int SHA256_HEX_BYTES = 64;
    private static final int FIXED_HEADER_BYTES = UUID_BYTES + Long.BYTES + Integer.BYTES + Integer.BYTES + SHA256_HEX_BYTES;

    private final ChecksumService checksumService;

    public FileTransferProtocol(ChecksumService checksumService) {
        this.checksumService = checksumService;
    }

    public FileChunk createChunk(UUID fileId, long totalSize, int chunkIndex, int chunkTotal, byte[] payload) {
        return new FileChunk(fileId, totalSize, chunkIndex, chunkTotal, checksumService.sha256(payload), payload);
    }

    public byte[] encode(FileChunk chunk) {
        byte[] checksum = chunk.checksumSha256().getBytes(StandardCharsets.US_ASCII);
        if (checksum.length != SHA256_HEX_BYTES) {
            throw new IllegalArgumentException("checksum must be a SHA-256 hex string");
        }

        ByteBuffer buffer = ByteBuffer.allocate(FIXED_HEADER_BYTES + Integer.BYTES + chunk.payload().length);
        buffer.putLong(chunk.fileId().getMostSignificantBits());
        buffer.putLong(chunk.fileId().getLeastSignificantBits());
        buffer.putLong(chunk.totalSize());
        buffer.putInt(chunk.chunkIndex());
        buffer.putInt(chunk.chunkTotal());
        buffer.put(checksum);
        buffer.putInt(chunk.payload().length);
        buffer.put(chunk.payload());
        return buffer.array();
    }

    public FileChunk decode(byte[] frame) {
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        UUID fileId = new UUID(buffer.getLong(), buffer.getLong());
        long totalSize = buffer.getLong();
        int chunkIndex = buffer.getInt();
        int chunkTotal = buffer.getInt();
        byte[] checksumBytes = new byte[SHA256_HEX_BYTES];
        buffer.get(checksumBytes);
        String checksum = new String(checksumBytes, StandardCharsets.US_ASCII);
        int payloadLength = buffer.getInt();
        byte[] payload = new byte[payloadLength];
        buffer.get(payload);

        if (!checksumService.matches(payload, checksum)) {
            throw new IllegalArgumentException("chunk checksum mismatch");
        }

        return new FileChunk(fileId, totalSize, chunkIndex, chunkTotal, checksum, payload);
    }
}
