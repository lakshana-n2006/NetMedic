package launcher;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;

public class Launcher extends JFrame {
    private final Map<String, Process> running = new HashMap<>();
    private final JTextArea console = new JTextArea();
    private final JLabel statusLabel = new JLabel("All stopped");

    public Launcher() {
        super("Self-Healing Trading System - Control Panel");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(820, 560);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel buttons = new JPanel(new GridLayout(0, 2, 8, 8));
        buttons.setBorder(BorderFactory.createTitledBorder("Components"));
        buttons.add(button("Start Backup Server", "start", "server.BackupServer"));
        buttons.add(button("Kill Backup Server", "stop", "server.BackupServer"));
        buttons.add(button("Start Primary Server", "start", "server.PrimaryServer"));
        buttons.add(button("Kill Primary (simulate crash)", "stop", "server.PrimaryServer"));
        buttons.add(button("Start Dashboard", "start", "server.DashboardGUI"));
        buttons.add(button("Start Client A", "start", "client.ChatGUI", "A"));
        buttons.add(button("Start Client B", "start", "client.ChatGUI", "B"));
        buttons.add(button("Clear Event Log", "clear", null));

        console.setEditable(false);
        console.setFont(new Font("Consolas", Font.PLAIN, 13));
        JScrollPane scroll = new JScrollPane(console);
        scroll.setBorder(BorderFactory.createTitledBorder("Launcher output"));

        statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));

        add(buttons, BorderLayout.WEST);
        add(scroll, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        setVisible(true);
    }

    private JButton button(String text, String action, String mainClass, String... args) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        b.addActionListener(e -> {
            if (action.equals("start")) start(text, mainClass, args);
            else if (action.equals("stop")) stop(mainClass, text);
            else clearEvents();
        });
        return b;
    }

    private void start(String label, String mainClass, String... args) {
        String key = mainClass + Arrays.toString(args);
        if (running.containsKey(key) && running.get(key).isAlive()) {
            log("Already running: " + label);
            return;
        }
        try {
            List<String> cmd = new ArrayList<>(Arrays.asList(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                "-cp", "out", mainClass));
            cmd.addAll(Arrays.asList(args));

            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process p = pb.start();
            running.put(key, p);
            pipeOutput(p, label);
            log("Started: " + label);
        } catch (IOException e) {
            log("Failed to start " + label + ": " + e.getMessage());
        }
    }

    private void stop(String mainClass, String label) {
        for (Map.Entry<String, Process> e : running.entrySet()) {
            if (e.getKey().startsWith(mainClass) && e.getValue().isAlive()) {
                e.getValue().destroyForcibly();
                log("Stopped: " + label);
                return;
            }
        }
        log("Not running: " + label);
    }

    private void pipeOutput(Process p, String label) {
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) log("[" + label + "] " + line);
            } catch (IOException ignored) {}
        }).start();
    }

    private void clearEvents() {
        new File("logs/events.log").delete();
        log("Event log cleared");
    }

    private void log(String text) {
        SwingUtilities.invokeLater(() -> {
            console.append(text + "\n");
            console.setCaretPosition(console.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(Launcher::new);
    }
}