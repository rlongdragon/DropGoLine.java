package p2p.transfer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.stereotype.Service;

import p2p.quic.QuicChannel;

@Service
public class FileTransferService {
    private static final String MAGIC = "DGL1";
    private static final int CHUNK_SIZE = 64 * 1024;
    private static final int MAX_FRAME_SIZE = 128 * 1024;

    private final FileTransferProtocol protocol;

    public FileTransferService(FileTransferProtocol protocol) {
        this.protocol = protocol;
    }

    public void send(Path source, QuicChannel channel) throws IOException {
        long totalSize = Files.size(source);
        int chunkTotal = Math.max(1, (int) ((totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE));
        UUID fileId = UUID.randomUUID();

        try (QuicChannel.TransferStream stream = channel.openStream();
             InputStream fileInput = Files.newInputStream(source)) {
            DataOutputStream out = new DataOutputStream(stream.output());
            DataInputStream in = new DataInputStream(stream.input());

            out.writeUTF(MAGIC);
            out.writeUTF(source.getFileName().toString());
            out.writeLong(fileId.getMostSignificantBits());
            out.writeLong(fileId.getLeastSignificantBits());
            out.writeLong(totalSize);
            out.writeInt(CHUNK_SIZE);
            out.writeInt(chunkTotal);
            out.flush();

            byte[] buffer = new byte[CHUNK_SIZE];
            for (int chunkIndex = 0; chunkIndex < chunkTotal; chunkIndex++) {
                int read = readChunk(fileInput, buffer);
                byte[] payload = new byte[read];
                System.arraycopy(buffer, 0, payload, 0, read);

                FileChunk chunk = protocol.createChunk(fileId, totalSize, chunkIndex, chunkTotal, payload);
                byte[] frame = protocol.encode(chunk);
                out.writeInt(frame.length);
                out.write(frame);
                out.flush();

                boolean accepted = in.readBoolean();
                int ackIndex = in.readInt();
                String message = in.readUTF();
                if (!accepted || ackIndex != chunkIndex) {
                    throw new IOException("chunk " + chunkIndex + " rejected: " + message);
                }
            }
        }
    }

    public Path receive(InputStream input, OutputStream output, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        DataInputStream in = new DataInputStream(input);
        DataOutputStream out = new DataOutputStream(output);

        String magic = in.readUTF();
        if (!MAGIC.equals(magic)) {
            throw new IOException("unsupported transfer protocol: " + magic);
        }

        String fileName = sanitizeFileName(in.readUTF());
        UUID fileId = new UUID(in.readLong(), in.readLong());
        long totalSize = in.readLong();
        in.readInt(); // chunk size; frames are self-delimiting below.
        int chunkTotal = in.readInt();

        TransferState state = new TransferState(fileId, chunkTotal);
        Path target = outputDirectory.resolve(fileName);

        try (OutputStream fileOutput = Files.newOutputStream(target)) {
            long bytesWritten = 0;
            for (int i = 0; i < chunkTotal; i++) {
                int frameLength = in.readInt();
                if (frameLength <= 0 || frameLength > MAX_FRAME_SIZE) {
                    throw new IOException("invalid frame length: " + frameLength);
                }

                byte[] frame = new byte[frameLength];
                in.readFully(frame);
                FileChunk chunk = protocol.decode(frame);
                if (!chunk.fileId().equals(fileId) || chunk.chunkIndex() != i) {
                    writeAck(out, i, false, "unexpected chunk");
                    throw new IOException("unexpected chunk " + chunk.chunkIndex());
                }

                fileOutput.write(chunk.payload());
                bytesWritten += chunk.payload().length;
                state.acknowledge(i);
                writeAck(out, i, true, "ok");
            }

            if (bytesWritten != totalSize || !state.isComplete()) {
                throw new IOException("incomplete transfer");
            }
        }

        return target;
    }

    private int readChunk(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = input.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return offset;
    }

    private void writeAck(DataOutputStream out, int chunkIndex, boolean accepted, String message) throws IOException {
        out.writeBoolean(accepted);
        out.writeInt(chunkIndex);
        out.writeUTF(message);
        out.flush();
    }

    private String sanitizeFileName(String fileName) {
        return Path.of(fileName).getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
