package legacy.chat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Server {
    private static final int PORT = 8888;
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(15);
    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final Map<String, List<ClientHandler>> rooms = new ConcurrentHashMap<>();
    private static final Map<String, String> userLocations = new ConcurrentHashMap<>();
    private static final Map<String, ClientHandler> pendingChannels = new ConcurrentHashMap<>();

    private static final ExecutorService clientExecutor = Executors.newCachedThreadPool();
    private static final ExecutorService bridgeExecutor = Executors.newCachedThreadPool();
    private static final Random random = new Random();

    private Server() {
    }

    public static void main(String[] args) throws IOException {
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("0.0.0.0", PORT));
            System.out.printf("[Server] Listening on port %d...%n", PORT);

            Thread heartbeat = new Thread(Server::heartbeatMonitor, "heartbeat-monitor");
            heartbeat.setDaemon(true);
            heartbeat.start();

            while (true) {
                Socket socket = listener.accept();
                clientExecutor.submit(new ClientHandler(socket));
            }
        }
    }

    private static void heartbeatMonitor() {
        while (true) {
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            Instant now = Instant.now();
            for (Map.Entry<String, List<ClientHandler>> entry : rooms.entrySet()) {
                String code = entry.getKey();
                List<ClientHandler> clients = entry.getValue();
                List<ClientHandler> zombies = new ArrayList<>();

                synchronized (clients) {
                    for (ClientHandler client : clients) {
                        if (Duration.between(client.lastActiveTime(), now).compareTo(HEARTBEAT_TIMEOUT) > 0) {
                            zombies.add(client);
                        }
                    }
                }

                for (ClientHandler zombie : zombies) {
                    System.out.printf("[Heartbeat] Removing zombie %s from %s%n", zombie.displayName(), code);
                    zombie.forceClose();
                    cleanup(code, zombie);
                }
            }
        }
    }

    private static void createRoom(String code, ClientHandler client) {
        rooms.putIfAbsent(code, new ArrayList<>(List.of(client)));
        System.out.printf("[Room] %s created by %s%n", code, client.name());
    }

    private static boolean isCodeExists(String code) {
        return rooms.containsKey(code);
    }

    private static boolean joinRoom(String code, ClientHandler client) {
        List<ClientHandler> clients = rooms.get(code);
        if (clients == null) {
            return false;
        }
        synchronized (clients) {
            clients.add(client);
        }
        return true;
    }

    private static List<ClientHandler> getPeers(String code, ClientHandler me) {
        List<ClientHandler> result = new ArrayList<>();
        List<ClientHandler> clients = rooms.get(code);
        if (clients == null) {
            return result;
        }
        synchronized (clients) {
            for (ClientHandler client : clients) {
                if (client != me) {
                    result.add(client);
                }
            }
        }
        return result;
    }

    private static void cleanup(String code, ClientHandler client) {
        if (client.name() != null && !client.name().isBlank()) {
            userLocations.remove(client.name());
        }

        if (code == null || code.isBlank()) {
            return;
        }

        List<ClientHandler> clients = rooms.get(code);
        if (clients == null) {
            return;
        }

        boolean empty;
        synchronized (clients) {
            clients.remove(client);
            empty = clients.isEmpty();
        }

        System.out.printf("[Room] %s left %s%n", client.name(), code);
        if (empty) {
            rooms.remove(code);
            System.out.printf("[Room] %s destroyed%n", code);
            return;
        }

        for (ClientHandler peer : getPeers(code, client)) {
            peer.sendMessage("DISCONNECT|" + client.name());
        }
    }

    private static void bridgeConnections(ClientHandler sender, ClientHandler receiver) {
        System.out.printf("[Relay] Bridging %s and %s%n", sender.ipAddress(), receiver.ipAddress());
        bridgeExecutor.submit(() -> copyStream(sender.socket(), receiver.socket()));
        bridgeExecutor.submit(() -> copyStream(receiver.socket(), sender.socket()));
    }

    private static void copyStream(Socket inputSocket, Socket outputSocket) {
        try {
            inputSocket.getInputStream().transferTo(outputSocket.getOutputStream());
        } catch (IOException ignored) {
        } finally {
            closeQuietly(inputSocket);
            closeQuietly(outputSocket);
            System.out.println("[Relay] Bridge closed.");
        }
    }

    private static String generateCode() {
        String code;
        do {
            char[] chars = new char[4];
            for (int i = 0; i < chars.length; i++) {
                chars[i] = CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length()));
            }
            code = new String(chars);
        } while (isCodeExists(code));
        return code;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static final class ClientHandler implements Runnable {
        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;
        private final String ipAddress;

        private volatile String name;
        private volatile String localIp;
        private volatile int localPort;
        private volatile String currentCode;
        private volatile boolean discoverable = true;
        private volatile Instant lastActiveTime = Instant.now();
        private volatile boolean stopRead;

        private ClientHandler(Socket socket) throws IOException {
            this.socket = socket;
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            InetSocketAddress remote = (InetSocketAddress) socket.getRemoteSocketAddress();
            this.ipAddress = remote.getAddress().getHostAddress();
        }

        @Override
        public void run() {
            System.out.printf("[Connect] New connection from %s%n", ipAddress);
            try {
                String line;
                while (!stopRead && (line = reader.readLine()) != null) {
                    lastActiveTime = Instant.now();
                    if (!line.startsWith("PING")) {
                        System.out.printf("[Recv] %s: %s%n", displayName(), line);
                    }
                    processCommand(line);
                }
            } catch (IOException e) {
                if (!stopRead) {
                    System.out.printf("[Error] %s: %s%n", name, e.getMessage());
                }
            } finally {
                if (!stopRead) {
                    cleanup(currentCode, this);
                    closeQuietly(socket);
                }
            }
        }

        private void processCommand(String command) {
            String[] parts = command.split("\\|", -1);
            String cmd = parts[0];

            switch (cmd) {
                case "CHANNEL_CREATE" -> {
                    if (parts.length < 2) {
                        sendMessage("ERROR|Missing channel id");
                        return;
                    }
                    String transId = parts[1];
                    pendingChannels.put(transId, this);
                    System.out.printf("[Relay] Channel Created: %s%n", transId);
                    sendMessage("RELAY_WAIT");
                    stopRead = true;
                }
                case "CHANNEL_JOIN" -> {
                    if (parts.length < 2) {
                        sendMessage("ERROR|Missing channel id");
                        return;
                    }
                    ClientHandler sender = pendingChannels.remove(parts[1]);
                    if (sender == null) {
                        sendMessage("ERROR|Channel not found");
                        return;
                    }
                    System.out.printf("[Relay] Channel Joined: %s%n", parts[1]);
                    sendMessage("RELAY_START");
                    stopRead = true;
                    bridgeExecutor.submit(() -> bridgeConnections(sender, this));
                }
                case "QUERY_PEERS" -> handlePeerQuery(parts);
                case "PING" -> {
                }
                case "REGISTER" -> {
                    if (parts.length < 4) {
                        sendMessage("ERROR|Invalid REGISTER");
                        return;
                    }
                    name = parts[1];
                    localIp = parts[2];
                    localPort = parsePort(parts[3], 0);
                    discoverable = parts.length < 5 || "1".equals(parts[4]);
                    System.out.printf("[Register] %s Public:%s Local:%s:%d Discoverable:%s%n",
                            name, ipAddress, localIp, localPort, discoverable);
                }
                case "CREATE" -> {
                    String code = generateCode();
                    currentCode = code;
                    createRoom(code, this);
                    if (discoverable && name != null && !name.isBlank()) {
                        userLocations.put(name, code);
                    }
                    sendMessage("CODE|" + code);
                }
                case "JOIN" -> {
                    if (parts.length < 2) {
                        sendMessage("ERROR|Missing room code");
                        return;
                    }
                    String joinCode = parts[1].toUpperCase();
                    if (!joinRoom(joinCode, this)) {
                        sendMessage("ERROR|Room not found");
                        return;
                    }

                    currentCode = joinCode;
                    List<ClientHandler> peers = getPeers(joinCode, this);
                    for (ClientHandler peer : peers) {
                        sendMessage(peer.matchMessage());
                        peer.sendMessage(matchMessage());
                    }

                    if (discoverable && name != null && !name.isBlank()) {
                        userLocations.put(name, joinCode);
                    }
                    System.out.printf("[Join] %s joined %s. Total peers: %d%n", name, joinCode, peers.size());
                }
                case "RELAY" -> {
                    String content = command.length() > 6 ? command.substring(6) : "";
                    for (ClientHandler target : getPeers(currentCode, this)) {
                        target.sendMessage("RELAY|" + name + "|" + content);
                    }
                }
                default -> sendMessage("ERROR|Unknown command");
            }
        }

        private void handlePeerQuery(String[] parts) {
            if (parts.length < 2) {
                return;
            }

            List<String> found = new ArrayList<>();
            for (String queryName : parts[1].split(",")) {
                if (queryName.isBlank()) {
                    continue;
                }
                String roomCode = userLocations.get(queryName);
                if (roomCode == null) {
                    continue;
                }
                int count = 0;
                List<ClientHandler> clients = rooms.get(roomCode);
                if (clients != null) {
                    synchronized (clients) {
                        count = clients.size();
                    }
                }
                found.add(queryName + ":" + roomCode + ":" + count);
            }

            if (!found.isEmpty()) {
                sendMessage("PEERS_FOUND|" + String.join(",", found));
            }
        }

        private String matchMessage() {
            return "MATCH|" + ipAddress + "|" + localPort + "|" + localIp + "|" + localPort + "|" + name;
        }

        private void sendMessage(String message) {
            try {
                synchronized (writer) {
                    writer.write(message);
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException ignored) {
            }
        }

        private int parsePort(String value, int fallback) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private void forceClose() {
            closeQuietly(socket);
        }

        private Socket socket() {
            return socket;
        }

        private String ipAddress() {
            return ipAddress;
        }

        private String name() {
            return name;
        }

        private Instant lastActiveTime() {
            return lastActiveTime;
        }

        private String displayName() {
            return name == null || name.isBlank() ? ipAddress : name;
        }
    }
}
