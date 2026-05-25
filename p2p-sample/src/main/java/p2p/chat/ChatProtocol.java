package p2p.chat;

final class ChatProtocol {
    static final String MAGIC = "DGLCHAT1";
    static final int CHUNK_SIZE = 64 * 1024;
    static final int TYPE_TEXT = 1;
    static final int TYPE_FILE_OFFER = 2;
    static final int TYPE_FILE_REQUEST = 3;
    static final int TYPE_FILE_START = 4;
    static final int TYPE_FILE_CHUNK = 5;
    static final int TYPE_FILE_END = 6;
    static final int TYPE_NOTICE = 7;

    private ChatProtocol() {
    }
}
