package legacy.chat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class Client {
    private static final int SERVER_PORT = 8888;

    private static final Map<String, Socket> peers = new ConcurrentHashMap<>();
    private static final Map<String, BufferedWriter> peerWriters = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> directConnectedPeers = new ConcurrentHashMap<>();

    private static final ExecutorService executor = Executors.newCachedThreadPool();

    private static volatile Socket serverSocket;
    private static volatile BufferedReader serverReader;
    private static volatile BufferedWriter serverWriter;
    private static volatile ServerSocket p2pListener;

    private static String myName;
    private static String myLocalIp;
    private static int myLocalPort;

    private Client() {
    }

    @SuppressWarnings("resource")
    public static void main(String[] args) {
        System.out.println("=== P2P Chat Client (Smart Hybrid) ===");
        Runtime.getRuntime().addShutdownHook(new Thread(Client::closeAll, "legacy-client-shutdown"));

        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {

            System.out.print("Enter your name: ");
            myName = scanner.nextLine();

            setupP2PListener();

            System.out.print("Enter Server IP (default 127.0.0.1): ");
            String serverIp = scanner.nextLine();
            if (serverIp.isBlank()) {
                serverIp = "127.0.0.1";
            }

            try {
                serverSocket = new Socket(serverIp, SERVER_PORT);
                serverReader = new BufferedReader(new InputStreamReader(serverSocket.getInputStream(), StandardCharsets.UTF_8));
                serverWriter = new BufferedWriter(new OutputStreamWriter(serverSocket.getOutputStream(), StandardCharsets.UTF_8));

                sendToServer("REGISTER|" + myName + "|" + myLocalIp + "|" + myLocalPort);
                executor.submit(Client::serverListenLoop);
                executor.submit(Client::heartbeatLoop);
            } catch (IOException e) {
                System.out.println("Cannot connect to server: " + e.getMessage());
                return;
            }

            System.out.println("1. Create Room");
            System.out.println("2. Join Room");
            String choice = scanner.nextLine();

            if ("1".equals(choice)) {
                sendToServer("CREATE");
                System.out.println("Creating room... waiting for code.");
            } else if ("2".equals(choice)) {
                System.out.print("Enter Code: ");
                String code = scanner.nextLine();
                sendToServer("JOIN|" + code);
            }

            while (scanner.hasNextLine()) {
                String msg = scanner.nextLine();
                if (!msg.isBlank()) {
                    broadcastMessage(msg);
                }
            }
        }
    }

    private static void heartbeatLoop() {
        while (true) {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    sendToServer("PING");
                }
                TimeUnit.SECONDS.sleep(3);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void setupP2PListener() {
        try {
            System.out.println("[System] Scanning Network Interfaces...");
            myLocalIp = findLocalIp();

            p2pListener = new ServerSocket(0);
            myLocalPort = p2pListener.getLocalPort();
            System.out.printf("[System] P2P Listener Bound to 0.0.0.0:%d%n", myLocalPort);

            Thread acceptThread = new Thread(() -> {
                while (!p2pListener.isClosed()) {
                    try {
                        Socket client = p2pListener.accept();
                        setupDirectConnection(client);
                    } catch (IOException ignored) {
                        break;
                    }
                }
            }, "p2p-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

            System.out.printf("[System] Reporting Local IP as: %s:%d%n", myLocalIp, myLocalPort);
        } catch (IOException e) {
            System.out.println("[Error] Setup P2P: " + e.getMessage());
        }
    }

    private static String findLocalIp() {
        String fallback = "127.0.0.1";
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                System.out.printf("[Debug] Interface: %s, Status: %s%n",
                        networkInterface.getName(), networkInterface.isUp() ? "Up" : "Down");

                Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address) {
                        System.out.println("      - IPv4: " + address.getHostAddress());
                    }
                }

                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }

                addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String selected = address.getHostAddress();
                        System.out.println("[System] Selected Local IP: " + selected);
                        return selected;
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[Warn] Cannot inspect network interfaces: " + e.getMessage());
        }
        return fallback;
    }

    private static void serverListenLoop() {
        try {
            String line;
            while ((line = serverReader.readLine()) != null) {
                String[] parts = line.split("\\|", -1);
                String cmd = parts[0];

                switch (cmd) {
                    case "CODE" -> {
                        if (parts.length >= 2) {
                            System.out.printf("%n[Room Created] Code: %s (Share this code)%n", parts[1]);
                        }
                    }
                    case "MATCH" -> {
                        if (parts.length >= 5) {
                            String publicIp = parts[1];
                            int publicPort = parsePort(parts[2], 0);
                            String localIp = parts[3];
                            int localPort = parsePort(parts[4], 0);
                            attemptP2P(publicIp, publicPort, localIp, localPort);
                        }
                    }
                    case "RELAY" -> {
                        if (parts.length >= 3) {
                            String sender = parts[1];
                            String content = line.substring(parts[0].length() + parts[1].length() + 2);
                            if (!directConnectedPeers.containsKey(sender)) {
                                System.out.printf("[%s](Relay): %s%n", sender, content);
                            }
                        }
                    }
                    case "ERROR" -> {
                        if (parts.length >= 2) {
                            System.out.println("[Error] " + parts[1]);
                        }
                    }
                    case "DISCONNECT" -> {
                        if (parts.length >= 2) {
                            System.out.println("[System] " + parts[1]);
                        }
                    }
                    case "PEERS_FOUND" -> System.out.println("[System] " + line);
                    default -> {
                    }
                }
            }
        } catch (IOException ignored) {
            System.out.println("[System] Disconnected from Server");
        }
    }

    private static void attemptP2P(String publicIp, int publicPort, String localIp, int localPort) {
        executor.submit(() -> {
            List<Future<Socket>> tasks = new ArrayList<>();
            tasks.add(executor.submit(() -> connectTimeout(publicIp, publicPort, 3000)));
            if (!localIp.equals(publicIp)) {
                tasks.add(executor.submit(() -> connectTimeout(localIp, localPort, 3000)));
            }

            for (Future<Socket> task : tasks) {
                try {
                    Socket client = task.get(3500, TimeUnit.MILLISECONDS);
                    if (client != null && client.isConnected()) {
                        setupDirectConnection(client);
                        return;
                    }
                } catch (InterruptedException | ExecutionException | TimeoutException ignored) {
                }
            }
        });
    }

    private static Socket connectTimeout(String ip, int port, int timeoutMs) {
        if (ip == null || ip.isBlank() || port <= 0) {
            return null;
        }
        try {
            Socket socket = new Socket();
            SocketAddress address = new InetSocketAddress(ip, port);
            socket.connect(address, timeoutMs);
            return socket;
        } catch (IOException e) {
            return null;
        }
    }

    private static void setupDirectConnection(Socket client) {
        try {
            String endpoint = client.getRemoteSocketAddress().toString();
            if (peers.putIfAbsent(endpoint, client) != null) {
                client.close();
                return;
            }

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));
            peerWriters.put(endpoint, writer);
            writeLine(writer, "NAME|" + myName);

            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            executor.submit(() -> directListenLoop(reader, endpoint));
        } catch (IOException ignored) {
        }
    }

    private static void directListenLoop(BufferedReader reader, String endpoint) {
        String connectedPeerName = null;
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MSG|")) {
                    String[] parts = line.split("\\|", 3);
                    if (parts.length >= 3) {
                        String sender = parts[1];
                        String content = parts[2];
                        System.out.printf("[%s](Direct): %s%n", sender, content);
                        if (connectedPeerName == null) {
                            connectedPeerName = sender;
                            directConnectedPeers.put(sender, true);
                        }
                    }
                } else if (line.startsWith("NAME|")) {
                    String[] parts = line.split("\\|", 2);
                    if (parts.length >= 2) {
                        connectedPeerName = parts[1];
                        directConnectedPeers.put(connectedPeerName, true);
                        System.out.printf("[System] Direct Link established with %s%n", connectedPeerName);
                    }
                } else {
                    System.out.println("[Peer](Raw): " + line);
                }
            }
        } catch (IOException ignored) {
        } finally {
            Socket socket = peers.remove(endpoint);
            if (socket != null) {
                closeQuietly(socket);
            }
            peerWriters.remove(endpoint);

            if (connectedPeerName != null) {
                directConnectedPeers.remove(connectedPeerName);
                System.out.printf("[System] Direct Link lost with %s. Reverting to Relay.%n", connectedPeerName);
            }
        }
    }

    private static void broadcastMessage(String msg) {
        for (BufferedWriter writer : peerWriters.values()) {
            try {
                writeLine(writer, "MSG|" + myName + "|" + msg);
            } catch (IOException ignored) {
            }
        }
        sendToServer("RELAY|" + msg);
    }

    private static void sendToServer(String message) {
        try {
            writeLine(serverWriter, message);
        } catch (IOException ignored) {
        }
    }

    private static void writeLine(BufferedWriter writer, String message) throws IOException {
        synchronized (writer) {
            writer.write(message);
            writer.newLine();
            writer.flush();
        }
    }

    private static int parsePort(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeAll() {
        closeQuietly(serverSocket);
        closeQuietly(p2pListener);
        for (Socket socket : peers.values()) {
            closeQuietly(socket);
        }
        executor.shutdownNow();
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
