package server;

import common.Constants;
import common.EventLog;
import common.Message;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class PrimaryServer {
    private final Map<String, PrintWriter> clients = new ConcurrentHashMap<>();
    private long seqCounter = 0;
    private PrintWriter backupOut;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();

    public void setSeqCounter(long last) {
        this.seqCounter = last;
    }

    public void start() throws IOException {
        connectToBackup();

        heartbeat.scheduleAtFixedRate(() -> {
            if (backupOut != null) {
                backupOut.println(new Message(Message.Type.HB, 0, "PRIMARY", "BACKUP", "ALIVE").encode());
            }
        }, 0, Constants.HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        ServerSocket server = new ServerSocket(Constants.PRIMARY_PORT);
        server.setReuseAddress(true);
        serve(server);
    }

    // Accepts clients on an already-bound port. Used by a promoted backup.
    public void serve(ServerSocket server) throws IOException {
        System.out.println("Primary listening on " + Constants.PRIMARY_PORT);
        EventLog.log("CONN", "Primary listening on " + Constants.PRIMARY_PORT);
        while (true) {
            Socket socket = server.accept();
            new Thread(() -> handleClient(socket)).start();
        }
    }

    private void connectToBackup() {
        try {
            Socket s = new Socket("localhost", Constants.BACKUP_PORT);
            backupOut = new PrintWriter(s.getOutputStream(), true);
            System.out.println("Connected to backup");
            EventLog.log("CONN", "Connected to backup");
        } catch (IOException e) {
            System.out.println("Backup not running yet: " + e.getMessage());
            EventLog.log("WARN", "Backup not running yet");
        }
    }

    private void handleClient(Socket socket) {
        String clientName = null;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            // First line from a client is its name, e.g. "A" or "B"
            clientName = in.readLine();
            clients.put(clientName, out);
            System.out.println(clientName + " connected");
            EventLog.log("CONN", clientName + " connected");

            String line;
            while ((line = in.readLine()) != null) {
                Message msg = Message.decode(line);
                if (msg.type == Message.Type.MSG) {
                    long seq = nextSeq();
                    Message stored = new Message(Message.Type.MSG, seq, msg.sender, msg.receiver, msg.payload);
                    logMessage(stored);
                    PrintWriter target = clients.get(msg.receiver);
                    if (target != null) target.println(stored.encode());
                    out.println(new Message(Message.Type.ACK, seq, "PRIMARY", msg.sender, "").encode());
                }
            }
        } catch (IOException e) {
            System.out.println("Connection lost: " + clientName);
        } finally {
            if (clientName != null) {
                clients.remove(clientName);
                EventLog.log("LOST", clientName + " disconnected");
            }
        }
    }

    private synchronized long nextSeq() {
        return ++seqCounter;
    }

    private synchronized void logMessage(Message m) {
        String record = m.encode();
        try (PrintWriter log = new PrintWriter(new FileWriter("logs/messages.log", true), true)) {
            log.println(record);
        } catch (IOException e) {
            System.err.println("Log write failed: " + e.getMessage());
        }
        EventLog.log("MSG", m.sender + " -> " + m.receiver + ": " + m.payload);
        if (backupOut != null) {
            backupOut.println(new Message(Message.Type.SYNC, m.seq, "PRIMARY", "BACKUP", record).encode());
        }
    }

    public static void main(String[] args) throws IOException {
        new PrimaryServer().start();
    }
}