package server;

import common.Constants;
import common.EventLog;
import common.Message;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class BackupServer {
    private volatile long lastHeartbeat = 0;
    private volatile long lastSeq = 0;
    private volatile boolean primarySeen = false;
    private volatile boolean promoted = false;
    private volatile boolean warned = false;
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService activePulse = Executors.newSingleThreadScheduledExecutor();

    public void start() throws IOException {
        ServerSocket server = new ServerSocket(Constants.BACKUP_PORT);
        server.setReuseAddress(true);
        System.out.println("Backup listening on " + Constants.BACKUP_PORT);
        EventLog.log("CONN", "Backup listening on " + Constants.BACKUP_PORT);

        // Check the heartbeat every 100 ms so the failover is fast
        monitor.scheduleAtFixedRate(this::checkPrimary, 100, 100, TimeUnit.MILLISECONDS);

        while (true) {
            Socket socket = server.accept();
            new Thread(() -> handlePrimary(socket)).start();
        }
    }

    private void checkPrimary() {
        if (promoted || !primarySeen) return;
        long silence = System.currentTimeMillis() - lastHeartbeat;

        if (silence > Constants.HEARTBEAT_INTERVAL_MS * 2 && !warned) {
            warned = true;
            EventLog.log("WARN", "No heartbeat for " + silence + " ms, primary may be down");
        }
        if (silence > Constants.FAILOVER_TIMEOUT_MS) {
            promote(silence);
        }
    }

    // Becomes the primary: binds port 5000 and serves clients
    private synchronized void promote(long silence) {
        if (promoted) return;
        long detectedAt = System.currentTimeMillis();
        EventLog.log("LOST", "Primary failed: silent for " + silence + " ms. Promoting backup");

        ServerSocket clientPort = null;
        for (int attempt = 0; attempt < 30 && clientPort == null; attempt++) {
            try {
                clientPort = new ServerSocket(Constants.PRIMARY_PORT);
                clientPort.setReuseAddress(true);
            } catch (IOException e) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
        }
        if (clientPort == null) {
            EventLog.log("LOST", "Promotion failed: port " + Constants.PRIMARY_PORT + " still in use");
            return;
        }

        promoted = true;
        long readyAt = System.currentTimeMillis();
        EventLog.log("FAILOVER", "Backup promoted to PRIMARY on port " + Constants.PRIMARY_PORT
            + " in " + (readyAt - detectedAt) + " ms (outage since last heartbeat: "
            + (readyAt - lastHeartbeat) + " ms)");
        System.out.println("BACKUP PROMOTED TO PRIMARY");

        // The new primary reports that it is alive, the same way the old primary sent HB
        activePulse.scheduleAtFixedRate(
            () -> EventLog.log("ACTIVE", "backup serving as primary on port " + Constants.PRIMARY_PORT),
            0, Constants.HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        PrimaryServer newPrimary = new PrimaryServer();
        newPrimary.setSeqCounter(lastSeq);
        ServerSocket port = clientPort;
        new Thread(() -> {
            try {
                newPrimary.serve(port);
            } catch (IOException e) {
                EventLog.log("LOST", "New primary stopped: " + e.getMessage());
            }
        }).start();
    }

    private void handlePrimary(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            System.out.println("Primary connected to backup");
            EventLog.log("CONN", "Primary connected to backup");
            String line;
            while ((line = in.readLine()) != null) {
                Message m = Message.decode(line);
                if (m.type == Message.Type.HB) {
                    primarySeen = true;
                    warned = false;
                    lastHeartbeat = System.currentTimeMillis();
                    EventLog.log("HB", "primary alive");
                } else if (m.type == Message.Type.SYNC) {
                    lastSeq = m.seq;
                    appendLog(m.payload);
                    EventLog.log("SYNC", "record " + m.seq + " replicated to backup");
                }
            }
        } catch (IOException e) {
            EventLog.log("LOST", "Primary link to backup lost");
        }
    }

    private synchronized void appendLog(String record) {
        try (PrintWriter log = new PrintWriter(new FileWriter("logs/backup.log", true), true)) {
            log.println(record);
        } catch (IOException e) {
            System.err.println("Backup log write failed: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws IOException {
        new BackupServer().start();
    }
}