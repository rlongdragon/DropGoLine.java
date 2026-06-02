package dropgoline.util;

public final class PeerIdsSelfTest {
    private PeerIdsSelfTest() {
    }

    public static void main(String[] args) {
        assertEquals("User#3368b", PeerIds.canonicalize("User%233368b"));
        assertEquals("User#3368b", PeerIds.canonicalize("User%25233368b"));
        assertEquals("User-[]6072", PeerIds.canonicalize("User-%5B%5D6072"));
        assertEquals("User-[]6072", PeerIds.canonicalize("User-%255B%255D6072"));

        assertEquals("User", PeerIds.displayName("User#3368b"));
        assertEquals("User", PeerIds.displayName("User%233368b"));
        assertEquals("User", PeerIds.displayName("User%25233368b"));
        assertEquals("User", PeerIds.displayName("User-[]6072"));
        assertEquals("User", PeerIds.displayName("User-%5B%5D6072"));
        assertEquals("User", PeerIds.displayName("User-%255B%255D6072"));
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
        }
    }
}
