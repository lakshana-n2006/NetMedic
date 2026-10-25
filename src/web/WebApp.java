package web;

import common.Constants;
import common.EventLog;
import common.Message;
import common.UserStore;
import common.UserStore.User;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

// Serves the trading desk on localhost and bridges trader browsers to the primary server
public class WebApp {
    static final int PORT = 8081;
    static final Path USERS = Paths.get("users.txt");
    static final Path RECEIVED = Paths.get("received");
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;   // 5 MB limit for receipts
    static final Map<String, User> sessions = new ConcurrentHashMap<>();
    static final Map<String, Bridge> bridges = new ConcurrentHashMap<>();
    static final Map<String, Path> files = new ConcurrentHashMap<>();   // fileId -> stored file
    private static final Object USERS_LOCK = new Object();

    public static void main(String[] args) throws IOException {
        Files.createDirectories(RECEIVED);
        HttpServer http = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        http.createContext("/", ex -> serveFile(ex, "src/web/index.html", "text/html; charset=utf-8"));
        http.createContext("/api/login", WebApp::login);
        http.createContext("/api/signup", WebApp::signup);
        http.createContext("/api/send", WebApp::send);
        http.createContext("/api/upload", WebApp::upload);
        http.createContext("/api/file", WebApp::download);
        http.createContext("/api/inbox", WebApp::inbox);
        http.createContext("/api/events", WebApp::events);
        http.setExecutor(Executors.newCachedThreadPool());
        http.start();

        String url = "http://localhost:" + PORT;
        System.out.println("Trading desk running at " + url);
        openWindow(url);
    }

    static void openWindow(String url) {
        String[] browsers = {
            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"
        };
        for (String b : browsers) {
            if (new File(b).exists()) {
                try {
                    new ProcessBuilder(b, "--app=" + url).start();
                    return;
                } catch (IOException ignored) {}
            }
        }
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            System.out.println("Open " + url + " in your browser");
        }
    }

    // ---------- Endpoints ----------

    static void login(HttpExchange ex) throws IOException {
        Map<String, String> p = params(ex);
        User u = UserStore.login(p.getOrDefault("user", "").trim(), p.getOrDefault("pass", ""));
        if (u == null) {
            json(ex, 401, "{\"error\":\"Invalid username or password.\"}");
            return;
        }
        issue(ex, u);
    }

    static void signup(HttpExchange ex) throws IOException {
        Map<String, String> p = params(ex);
        String name = p.getOrDefault("user", "").trim();
        String pass = p.getOrDefault("pass", "");

        if (!name.matches("[A-Za-z0-9_]{1,20}")) {
            json(ex, 400, "{\"error\":\"Username: 1-20 letters, digits or underscore.\"}");
            return;
        }
        if (pass.length() < 6) {
            json(ex, 400, "{\"error\":\"Password must be at least 6 characters.\"}");
            return;
        }
        synchronized (USERS_LOCK) {
            try {
                List<String> lines = Files.exists(USERS)
                    ? Files.readAllLines(USERS, StandardCharsets.UTF_8) : new ArrayList<>();
                for (String line : lines) {
                    if (line.split(":")[0].equalsIgnoreCase(name)) {
                        json(ex, 409, "{\"error\":\"That username already exists. Please sign in.\"}");
                        return;
                    }
                }
                String record = name + ":CLIENT:" + UserStore.hash(pass);
                Files.write(USERS, Collections.singletonList(record), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                json(ex, 500, "{\"error\":\"Could not save account.\"}");
                return;
            }
        }
        issue(ex, new User(name, UserStore.Role.CLIENT));
    }

    static void issue(HttpExchange ex, User u) throws IOException {
        String token = UUID.randomUUID().toString();
        sessions.put(token, u);
        if (u.role == UserStore.Role.CLIENT) bridges.computeIfAbsent(u.name, Bridge::new);
        json(ex, 200, "{\"token\":" + esc(token) + ",\"name\":" + esc(u.name) + ",\"role\":" + esc(u.role.name()) + "}");
    }

    static void send(HttpExchange ex) throws IOException {
        Map<String, String> p = params(ex);
        User u = sessions.get(p.getOrDefault("token", ""));
        if (u == null || u.role != UserStore.Role.CLIENT) {
            json(ex, 401, "{\"error\":\"Please sign in again.\"}");
            return;
        }
        String to = p.getOrDefault("to", "").trim();
        String text = p.getOrDefault("text", "").replace('\n', ' ').replace('\r', ' ').trim();
        if (to.isEmpty()) {
            json(ex, 400, "{\"error\":\"Enter the trader name in the To box.\"}");
            return;
        }
        if (text.isEmpty()) {
            json(ex, 400, "{\"error\":\"Type a message first.\"}");
            return;
        }
        Bridge b = bridges.get(u.name);
        if (b == null || !b.send(to, text)) {
            json(ex, 503, "{\"error\":\"Connection is reconnecting. Please send again in a moment.\"}");
            return;
        }
        json(ex, 200, "{\"ok\":true}");
    }

    // Receives a PDF receipt from the sender, stores it, and notifies the recipient through the primary
    static void upload(HttpExchange ex) throws IOException {
        String token = queryParam(ex, "token");
        String to = queryParam(ex, "to");
        String name = queryParam(ex, "name");
        User u = sessions.get(token);
        if (u == null || u.role != UserStore.Role.CLIENT) {
            json(ex, 401, "{\"error\":\"Please sign in again.\"}");
            return;
        }
        if (to.isEmpty()) {
            json(ex, 400, "{\"error\":\"Enter the trader name in the To box.\"}");
            return;
        }
        byte[] data = ex.getRequestBody().readAllBytes();
        if (data.length == 0 || data.length > MAX_FILE_BYTES) {
            json(ex, 400, "{\"error\":\"File must be between 1 byte and 5 MB.\"}");
            return;
        }
        // Only PDF files are accepted: check the magic number %PDF
        if (data.length < 4 || data[0] != '%' || data[1] != 'P' || data[2] != 'D' || data[3] != 'F') {
            json(ex, 400, "{\"error\":\"Only PDF files can be sent as receipts.\"}");
            return;
        }
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!safe.toLowerCase().endsWith(".pdf")) safe = safe + ".pdf";
        String id = UUID.randomUUID().toString();
        Path stored = RECEIVED.resolve(id + ".pdf");
        Files.write(stored, data);
        files.put(id, stored);

        Bridge b = bridges.get(u.name);
        String fileNotice = "[RECEIPT] " + safe + " (" + data.length + " bytes) id=" + id;
        if (b == null || !b.send(to, fileNotice)) {
            Files.deleteIfExists(stored);
            files.remove(id);
            json(ex, 503, "{\"error\":\"Connection is reconnecting. Please send again in a moment.\"}");
            return;
        }
        EventLog.log("MSG", u.name + " -> " + to + ": sent receipt " + safe);
        json(ex, 200, "{\"ok\":true}");
    }

    // Lets the recipient download a receipt by id. The page is served only to a signed-in trader.
    static void download(HttpExchange ex) throws IOException {
        String token = queryParam(ex, "token");
        String id = queryParam(ex, "id");
        User u = sessions.get(token);
        if (u == null) {
            json(ex, 401, "{\"error\":\"Please sign in again.\"}");
            return;
        }
        Path p = files.get(id);
        if (p == null || !Files.exists(p)) {
            json(ex, 404, "{\"error\":\"Receipt not found.\"}");
            return;
        }
        byte[] data = Files.readAllBytes(p);
        ex.getResponseHeaders().add("Content-Type", "application/pdf");
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"receipt.pdf\"");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    static void inbox(HttpExchange ex) throws IOException {
        Map<String, String> p = params(ex);
        User u = sessions.get(p.getOrDefault("token", ""));
        if (u == null) {
            json(ex, 401, "{\"error\":\"Please sign in again.\"}");
            return;
        }
        Bridge b = bridges.get(u.name);
        StringBuilder items = new StringBuilder();
        String s;
        while (b != null && (s = b.inbox.poll()) != null) {
            if (items.length() > 0) items.append(',');
            items.append(s);
        }
        json(ex, 200, "{\"online\":" + (b != null && b.online) + ",\"items\":[" + items + "]}");
    }

    static void events(HttpExchange ex) throws IOException {
        Map<String, String> p = params(ex);
        User u = sessions.get(p.getOrDefault("token", ""));
        if (u == null || u.role != UserStore.Role.ADMIN) {
            json(ex, 403, "{\"error\":\"Admins only.\"}");
            return;
        }
        int since = Integer.parseInt(p.getOrDefault("since", "0"));
        Path log = Paths.get("logs/events.log");
        List<String> lines = Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of();
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, since); i < lines.size(); i++) {
            if (sb.length() > 0) sb.append(',');
            sb.append(esc(lines.get(i)));
        }
        json(ex, 200, "{\"total\":" + lines.size() + ",\"lines\":[" + sb + "]}");
    }

    // ---------- Bridge: one TCP connection to the primary per trader ----------

    static class Bridge {
        final String name;
        final Deque<String> inbox = new ConcurrentLinkedDeque<>();
        volatile PrintWriter out;
        volatile boolean online;

        Bridge(String name) {
            this.name = name;
            Thread t = new Thread(this::loop);
            t.setDaemon(true);
            t.start();
        }

        boolean send(String to, String text) {
            PrintWriter w = out;
            if (w == null) return false;
            w.println(new Message(Message.Type.MSG, 0, name, to, text).encode());
            return true;
        }

        private synchronized void setOnline(boolean up) {
            if (online == up) return;
            online = up;
            EventLog.log(up ? "CONN" : "LOST",
                "Trader " + name + (up ? " connected to desk" : " disconnected from desk"));
        }

        void loop() {
            while (true) {
                try (Socket s = new Socket("localhost", Constants.PRIMARY_PORT)) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                    PrintWriter w = new PrintWriter(s.getOutputStream(), true);
                    w.println(name);
                    out = w;
                    setOnline(true);
                    String line;
                    while ((line = in.readLine()) != null) {
                        Message m = Message.decode(line);
                        if (m.type == Message.Type.MSG) {
                            inbox.add("{\"type\":\"MSG\",\"from\":" + esc(m.sender) + ",\"text\":" + esc(m.payload) + "}");
                        } else if (m.type == Message.Type.ACK) {
                            inbox.add("{\"type\":\"ACK\"}");
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    out = null;
                    setOnline(false);
                }
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        }
    }

    // ---------- HTTP helpers ----------

    static Map<String, String> params(HttpExchange ex) throws IOException {
        Map<String, String> m = new HashMap<>();
        String query = ex.getRequestURI().getRawQuery();
        String body = "POST".equals(ex.getRequestMethod())
            ? new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8) : "";
        String all = (query == null ? "" : query) + "&" + body;
        for (String part : all.split("&")) {
            if (part.isEmpty()) continue;
            String[] kv = part.split("=", 2);
            m.put(dec(kv[0]), kv.length > 1 ? dec(kv[1]) : "");
        }
        return m;
    }

    // Reads one parameter from the URL query only (used by the binary upload and download)
    static String queryParam(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return "";
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && dec(kv[0]).equals(key)) return dec(kv[1]).trim();
        }
        return "";
    }

    static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    static void json(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", body);
    }

    static void serveFile(HttpExchange ex, String path, String type) throws IOException {
        send(ex, 200, type, new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8));
    }

    static String esc(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", " ").replace("\r", " ").replace("\t", " ") + "\"";
    }
}