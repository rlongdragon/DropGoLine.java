package p2p.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P2pSessionInstancePolicyTest {
    @TempDir
    private Path tempDir;

    @Test
    void expiredDirectReconnectWindowAsksPeerToRejoinWithoutRemovingMembership() throws Exception {
        P2pSessionInstance session = new P2pSessionInstance(null, "alice", "ROOM", tempDir);
        CountDownLatch rejoinNotice = new CountDownLatch(1);
        session.onReceived(event -> {
            if (event.type() == P2pEvent.Type.NOTICE
                    && "bob".equals(event.from())
                    && event.message() != null
                    && event.message().contains("rejoin")) {
                rejoinNotice.countDown();
            }
        });

        knownMembers(session).add("bob");
        reconnectDeadlines(session).put("bob", System.nanoTime() - TimeUnit.SECONDS.toNanos(1));

        Method scheduleReconnect = P2pSessionInstance.class.getDeclaredMethod("scheduleReconnect", String.class);
        scheduleReconnect.setAccessible(true);
        scheduleReconnect.invoke(session, "bob");

        assertTrue(rejoinNotice.await(1, TimeUnit.SECONDS), "expired reconnect window did not emit rejoin notice");
        assertTrue(session.showMembers().contains("bob"), "direct reconnect expiry should not remove room membership");
        session.close();
    }

    @SuppressWarnings("unchecked")
    private Set<String> knownMembers(P2pSessionInstance session) throws Exception {
        Field field = P2pSessionInstance.class.getDeclaredField("knownMembers");
        field.setAccessible(true);
        return (Set<String>) field.get(session);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> reconnectDeadlines(P2pSessionInstance session) throws Exception {
        Field field = P2pSessionInstance.class.getDeclaredField("reconnectDeadlines");
        field.setAccessible(true);
        return (Map<String, Long>) field.get(session);
    }
}
