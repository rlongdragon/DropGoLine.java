package p2p.api;

import java.nio.file.Path;

public record P2pEvent(
        Type type,
        String from,
        String message,
        String offerId,
        String fileName,
        long fileSize,
        Path file,
        boolean direct
) {
    public static P2pEvent message(String from, String message, boolean direct) {
        return new P2pEvent(Type.MESSAGE, from, message, null, null, -1, null, direct);
    }

    public static P2pEvent fileOffer(String from, String offerId, String fileName, long fileSize, boolean direct) {
        return new P2pEvent(Type.FILE_OFFER, from, null, offerId, fileName, fileSize, null, direct);
    }

    public static P2pEvent fileSaved(String from, String offerId, Path file, boolean direct) {
        return new P2pEvent(Type.FILE_SAVED, from, null, offerId, file == null ? null : file.getFileName().toString(),
                -1, file, direct);
    }

    public static P2pEvent notice(String from, String message, boolean direct) {
        return new P2pEvent(Type.NOTICE, from, message, null, null, -1, null, direct);
    }

    public enum Type {
        MESSAGE,
        FILE_OFFER,
        FILE_SAVED,
        NOTICE,
        PEER_JOINED,
        PEER_LEFT
    }
}
