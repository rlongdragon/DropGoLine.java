package p2p.session;

public final class SessionProtocol {
    public static final String MAGIC = "DGLCHAT1";
    public static final int CHUNK_SIZE = 64 * 1024;
    public static final int TYPE_TEXT = 1;
    public static final int TYPE_FILE_OFFER = 2;
    public static final int TYPE_FILE_REQUEST = 3;
    public static final int TYPE_FILE_START = 4;
    public static final int TYPE_FILE_CHUNK = 5;
    public static final int TYPE_FILE_END = 6;
    public static final int TYPE_NOTICE = 7;
    public static final int TYPE_KEEPALIVE = 8;

    private SessionProtocol() {
    }
}
