package server;

import common.Constants;
import common.Ui;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.regex.*;

public class DashboardGUI extends JFrame {
    private static final Pattern TRADE = Pattern.compile("(\\S+) -> (\\S+): (.*)");
    private static final Pattern RECOVERY = Pattern.compile("in (\\d+) ms \\(outage since last heartbeat: (\\d+) ms\\)");
    private static final Pattern SILENT = Pattern.compile("silent for (\\d+) ms");
    private static final String[] TYPES = {"ALL", "HB", "ACTIVE", "CONN", "MSG", "SYNC", "WARN", "LOST", "FAILOVER"};
    private static final long LIMIT = Constants.FAILOVER_TIMEOUT_MS;

    private Ui.Theme theme = new Ui.Theme(true);
    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm:ss");
    private final List<JPanel> cards = new ArrayList<>();
    private final List<JLabel> sectionLabels = new ArrayList<>();

    private final JPanel root = new JPanel(new BorderLayout(0, 18));
    private final JLabel title = new JLabel("Trading Server Operations");
    private final JLabel subtitle = new JLabel("Live heartbeat health, failover and recovery monitor");
    private final JLabel clock = new JLabel();
    private final JButton themeBtn = new JButton("Light mode");

    private final Kpi kPrimary = new Kpi("Primary server");
    private final Kpi kBackup = new Kpi("Backup server");
    private final Kpi kHeartbeats = new Kpi("Heartbeats");
    private final Kpi kMessages = new Kpi("Orders routed");
    private final Kpi kFailovers = new Kpi("Failovers");
    private final Kpi kRecovery = new Kpi("Last recovery");

    private final HealthChart healthChart = new HealthChart(theme);
    private final BarChart recoveryChart = new BarChart(theme);

    private final DefaultTableModel eventModel = model("Time", "Type", "Details");
    private final DefaultTableModel tradeModel = model("Time", "From", "To", "Order");
    private final DefaultTableModel recModel = model("#", "Detected at", "Silence at detection (ms)",
        "Promotion time (ms)", "Total outage (ms)", "Promotion within 3 s");
    private final JTable eventTable = new JTable(eventModel);
    private final JTable tradeTable = new JTable(tradeModel);
    private final JTable recTable = new JTable(recModel);
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(eventModel);
    private final JComboBox<String> filterBox = new JComboBox<>(TYPES);
    private final JLabel filterLabel = new JLabel("Filter by type");
    private final JTextArea detail = new JTextArea(5, 60);
    private final JTabbedPane tabs = new JTabbedPane();
    private final Ui.Zebra eventZebra;
    private final Ui.Zebra plainZebra;

    // lastAlive = last sign of life from either the primary (HB) or the promoted backup (ACTIVE)
    private long lastAlive = 0;
    private long lastRecovery = -1;
    private int hbTotal = 0, msgTotal = 0, failovers = 0, linesRead = 0;
    private boolean promoted = false;
    private String pendingDetectTime = "-";
    private long pendingSilence = 0;

    public DashboardGUI() {
        super("Trading Server Operations - Admin");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1320, 860);
        setMinimumSize(new Dimension(1080, 720));
        setLocationRelativeTo(null);
        setContentPane(root);
        root.setBorder(new EmptyBorder(22, 26, 22, 26));

        eventZebra = new Ui.Zebra(theme) {
            @Override
            protected Color foreground(JTable tb, int row, int col, Ui.Theme t) {
                return typeColor(String.valueOf(tb.getValueAt(row, 1)), t);
            }
        };
        plainZebra = new Ui.Zebra(theme);

        eventTable.setRowSorter(sorter);
        eventTable.setDefaultRenderer(Object.class, eventZebra);
        tradeTable.setDefaultRenderer(Object.class, plainZebra);
        recTable.setDefaultRenderer(Object.class, plainZebra);
        eventTable.getColumnModel().getColumn(0).setPreferredWidth(110);
        eventTable.getColumnModel().getColumn(1).setPreferredWidth(110);
        eventTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showDetail();
        });

        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setFont(Ui.font(Font.PLAIN, 13));
        detail.setBorder(new EmptyBorder(10, 12, 10, 12));

        filterLabel.setFont(Ui.font(Font.BOLD, 12));
        sectionLabels.add(filterLabel);
        filterBox.addActionListener(e -> applyFilter());

        // ----- Header -----
        JPanel titles = new JPanel(new GridLayout(2, 1, 0, 3));
        titles.setOpaque(false);
        title.setFont(Ui.font(Font.BOLD, 22));
        subtitle.setFont(Ui.font(Font.PLAIN, 13));
        titles.add(title);
        titles.add(subtitle);

        clock.setFont(Ui.font(Font.BOLD, 14));
        themeBtn.addActionListener(e -> toggleTheme());
        JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        rightBox.setOpaque(false);
        rightBox.add(clock);
        rightBox.add(themeBtn);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(titles, BorderLayout.WEST);
        header.add(rightBox, BorderLayout.EAST);

        // ----- KPI row -----
        JPanel kpiRow = new JPanel(new GridLayout(1, 6, 14, 0));
        kpiRow.setOpaque(false);
        for (Kpi k : kpis()) kpiRow.add(k);

        JPanel top = new JPanel(new BorderLayout(0, 18));
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(kpiRow, BorderLayout.CENTER);
        root.add(top, BorderLayout.NORTH);

        // ----- Charts -----
        JPanel healthCard = sectionCard("HEARTBEAT HEALTH  -  silence between heartbeats, last 60 s", healthChart);
        JPanel recCard = sectionCard("RECOVERY TIME PER FAILOVER  -  promotion time in ms", recoveryChart);
        JSplitPane charts = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, healthCard, recCard);
        charts.setResizeWeight(0.64);
        charts.setBorder(null);
        charts.setDividerSize(12);

        // ----- Tabs -----
        tabs.setFont(Ui.font(Font.BOLD, 13));
        tabs.addTab("Event log", buildEventsTab());
        tabs.addTab("Trades", scroll(tradeTable));
        tabs.addTab("Recovery history", scroll(recTable));

        JSplitPane body = new JSplitPane(JSplitPane.VERTICAL_SPLIT, charts, tabs);
        body.setResizeWeight(0.45);
        body.setBorder(null);
        body.setDividerSize(12);
        root.add(body, BorderLayout.CENTER);

        applyTheme();
        new javax.swing.Timer(300, e -> refresh()).start();
        setVisible(true);
    }

    // ---------- Building blocks ----------

    private static DefaultTableModel model(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    private Kpi[] kpis() {
        return new Kpi[]{kPrimary, kBackup, kHeartbeats, kMessages, kFailovers, kRecovery};
    }

    private JPanel sectionCard(String text, JComponent body) {
        JPanel p = new JPanel(new BorderLayout(0, 12));
        JLabel l = new JLabel(text);
        l.setFont(Ui.font(Font.BOLD, 12));
        sectionLabels.add(l);
        p.add(l, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        cards.add(p);
        return p;
    }

    private JScrollPane scroll(JTable t) {
        JScrollPane s = new JScrollPane(t);
        s.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        return s;
    }

    private JPanel buildEventsTab() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        bar.setOpaque(false);
        bar.add(filterLabel);
        bar.add(filterBox);

        JScrollPane detailScroll = new JScrollPane(detail);
        detailScroll.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll(eventTable), detailScroll);
        split.setResizeWeight(0.78);
        split.setBorder(null);

        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(bar, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private void applyFilter() {
        String sel = (String) filterBox.getSelectedItem();
        RowFilter<Object, Object> f = "ALL".equals(sel) ? null : RowFilter.regexFilter("^" + sel + "$", 1);
        sorter.setRowFilter(f);
    }

    private void showDetail() {
        int r = eventTable.getSelectedRow();
        if (r < 0) return;
        detail.setText("Time      " + eventTable.getValueAt(r, 0)
            + "\nType      " + eventTable.getValueAt(r, 1)
            + "\nDetails   " + eventTable.getValueAt(r, 2));
        detail.setCaretPosition(0);
    }

    private static Color typeColor(String type, Ui.Theme t) {
        switch (type) {
            case "HB": return t.info;
            case "ACTIVE": return t.good;
            case "CONN": return t.good;
            case "SYNC": return t.purple;
            case "WARN": return t.warn;
            case "LOST": return t.bad;
            case "FAILOVER": return t.good;
            default: return t.text;
        }
    }

    // ---------- Live refresh ----------

    private void refresh() {
        readNewEvents();
        long now = System.currentTimeMillis();
        clock.setText(clockFmt.format(new Date()));

        long silence = lastAlive == 0 ? 0 : now - lastAlive;
        boolean silent = lastAlive > 0 && silence >= LIMIT;
        Ui.Theme t = theme;

        // Primary card
        if (lastAlive == 0) {
            kPrimary.set("WAITING", t.subText);
        } else if (!promoted) {
            if (silent) kPrimary.set("OFFLINE", t.bad);
            else kPrimary.set("ONLINE", t.good);
        } else {
            if (silent) kPrimary.set("ACTIVE LOST", t.bad);
            else kPrimary.set("BACKUP ACTIVE", t.good);
        }

        // Backup card
        if (promoted) {
            if (silent) kBackup.set("DOWN", t.bad);
            else kBackup.set("PROMOTED", t.good);
        } else if (lastAlive == 0) {
            kBackup.set("WAITING", t.subText);
        } else if (silent) {
            kBackup.set("TAKING OVER", t.warn);
        } else {
            kBackup.set("STANDBY", t.purple);
        }

        kHeartbeats.set(String.valueOf(hbTotal), t.info);
        kMessages.set(String.valueOf(msgTotal), t.accent);
        kFailovers.set(String.valueOf(failovers), failovers > 0 ? t.warn : t.subText);
        if (lastRecovery < 0) kRecovery.set("-", t.subText);
        else kRecovery.set(lastRecovery + " ms", lastRecovery <= LIMIT ? t.good : t.bad);

        healthChart.addSample(silence, promoted);
    }

    private void readNewEvents() {
        File f = new File("logs/events.log");
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                n++;
                if (n <= linesRead) continue;
                handleLine(line);
            }
            linesRead = n;
        } catch (IOException ignored) {}
    }

    private void handleLine(String line) {
        String[] p = line.split("\\|", 3);
        if (p.length < 3) return;
        String time = p[0], type = p[1], text = p[2];
        long now = System.currentTimeMillis();
        eventModel.addRow(new Object[]{time, type, text});

        switch (type) {
            case "HB":
                hbTotal++;
                lastAlive = now;
                break;
            case "ACTIVE":
                lastAlive = now;
                break;
            case "MSG": {
                msgTotal++;
                Matcher m = TRADE.matcher(text);
                if (m.matches()) tradeModel.addRow(new Object[]{time, m.group(1), m.group(2), m.group(3)});
                break;
            }
            case "LOST": {
                if (text.startsWith("Primary failed")) {
                    Matcher d = SILENT.matcher(text);
                    pendingSilence = d.find() ? Long.parseLong(d.group(1)) : 0;
                    pendingDetectTime = time;
                    healthChart.addMarker(0);
                }
                break;
            }
            case "FAILOVER": {
                promoted = true;
                failovers++;
                healthChart.addMarker(1);
                Matcher r = RECOVERY.matcher(text);
                if (r.find()) {
                    long promo = Long.parseLong(r.group(1));
                    long outage = Long.parseLong(r.group(2));
                    lastRecovery = promo;
                    recModel.addRow(new Object[]{
                        failovers, pendingDetectTime, pendingSilence, promo, outage,
                        promo <= LIMIT ? "YES" : "NO"});
                    recoveryChart.add("#" + failovers, promo);
                }
                break;
            }
            default:
                break;
        }
    }

    // ---------- Theme ----------

    private void toggleTheme() {
        theme = new Ui.Theme(!theme.dark);
        themeBtn.setText(theme.dark ? "Light mode" : "Dark mode");
        applyTheme();
    }

    private void applyTheme() {
        Ui.Theme t = theme;
        root.setBackground(t.bg);
        title.setForeground(t.text);
        subtitle.setForeground(t.subText);
        clock.setForeground(t.accent);
        Ui.styleButton(themeBtn, t.surfaceAlt, t.text);

        for (Kpi k : kpis()) k.applyTheme(t);
        for (JPanel c : cards) {
            c.setBackground(t.surface);
            c.setBorder(Ui.cardBorder(t));
        }
        for (JLabel l : sectionLabels) l.setForeground(t.subText);

        healthChart.setTheme(t);
        recoveryChart.setTheme(t);

        Ui.styleTable(eventTable, t);
        Ui.styleTable(tradeTable, t);
        Ui.styleTable(recTable, t);
        eventZebra.setTheme(t);
        plainZebra.setTheme(t);

        tabs.setBackground(t.bg);
        tabs.setForeground(t.text);
        detail.setBackground(t.surface);
        detail.setForeground(t.text);
        detail.setCaretColor(t.text);
        filterBox.setBackground(t.inputBg);
        filterBox.setForeground(t.text);
        root.repaint();
    }

    public static void main(String[] args) {
        Ui.installLookAndFeel();
        SwingUtilities.invokeLater(DashboardGUI::new);
    }

    // ================= Nested components =================

    static class Kpi extends JPanel {
        private final JLabel titleLabel = new JLabel();
        private final JLabel valueLabel = new JLabel("-");

        Kpi(String heading) {
            super(new BorderLayout(0, 8));
            titleLabel.setText(heading.toUpperCase());
            titleLabel.setFont(Ui.font(Font.BOLD, 11));
            valueLabel.setFont(Ui.font(Font.BOLD, 18));
            add(titleLabel, BorderLayout.NORTH);
            add(valueLabel, BorderLayout.CENTER);
        }

        void set(String v, Color c) {
            valueLabel.setText(v);
            valueLabel.setForeground(c);
        }

        void applyTheme(Ui.Theme t) {
            setBackground(t.surface);
            setBorder(Ui.cardBorder(t));
            titleLabel.setForeground(t.subText);
        }
    }

    // Silence between heartbeats over the last 60 s, with the 3 s limit and event markers
    static class HealthChart extends JPanel {
        private static final long WINDOW = 60_000L;
        private final Deque<long[]> samples = new ArrayDeque<>();   // {time, silenceMs, promoted}
        private final Deque<long[]> markers = new ArrayDeque<>();   // {time, kind 0=detected 1=failover}
        private Ui.Theme th;

        HealthChart(Ui.Theme t) {
            this.th = t;
            setPreferredSize(new Dimension(600, 280));
        }

        void setTheme(Ui.Theme t) { th = t; repaint(); }

        void addSample(long silence, boolean promoted) {
            long now = System.currentTimeMillis();
            samples.addLast(new long[]{now, silence, promoted ? 1 : 0});
            trim(now);
            repaint();
        }

        void addMarker(int kind) {
            markers.addLast(new long[]{System.currentTimeMillis(), kind});
            repaint();
        }

        private void trim(long now) {
            while (!samples.isEmpty() && now - samples.peekFirst()[0] > WINDOW) samples.pollFirst();
            while (!markers.isEmpty() && now - markers.peekFirst()[0] > WINDOW) markers.pollFirst();
        }

        private static int xOf(long t, long start, int left, int pw) {
            int x = left + (int) Math.round(pw * (double) (t - start) / WINDOW);
            return Math.max(left, Math.min(left + pw, x));
        }

        private static int yOf(double v, double yMax, int top, int ph) {
            return top + ph - (int) Math.round(ph * Math.min(v, yMax) / yMax);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int L = 56, R = 16, T = 16, B = 28;
            int pw = w - L - R, ph = h - T - B;
            g2.setColor(th.surface);
            g2.fillRect(0, 0, w, h);
            if (pw <= 10 || ph <= 10) { g2.dispose(); return; }

            long now = System.currentTimeMillis();
            long start = now - WINDOW;
            double maxSilence = LIMIT;
            for (long[] s : samples) maxSilence = Math.max(maxSilence, s[1]);
            double yMax = maxSilence * 1.15;

            g2.setFont(Ui.font(Font.PLAIN, 11));
            for (int i = 0; i <= 4; i++) {
                int y = T + ph - (int) Math.round(ph * i / 4.0);
                g2.setColor(th.border);
                g2.drawLine(L, y, L + pw, y);
                g2.setColor(th.subText);
                g2.drawString(Math.round(yMax * i / 4.0) + " ms", 6, y + 4);
            }
            for (int s = 0; s <= 60; s += 10) {
                int x = L + (int) Math.round(pw * s / 60.0);
                g2.setColor(th.border);
                g2.drawLine(x, T, x, T + ph);
                g2.setColor(th.subText);
                g2.drawString(s == 60 ? "now" : "-" + (60 - s) + "s", x - 12, h - 8);
            }

            // 3 second limit
            Stroke dashed = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                new float[]{6f, 5f}, 0f);
            int ly = yOf(LIMIT, yMax, T, ph);
            g2.setStroke(dashed);
            g2.setColor(th.bad);
            g2.drawLine(L, ly, L + pw, ly);
            g2.setFont(Ui.font(Font.BOLD, 11));
            g2.drawString("3 s limit", L + pw - 66, ly - 5);

            // Event markers, staggered so overlapping labels stay readable
            int labelRow = 0;
            for (long[] m : markers) {
                int x = xOf(m[0], start, L, pw);
                g2.setColor(m[1] == 0 ? th.warn : th.good);
                g2.setStroke(dashed);
                g2.drawLine(x, T, x, T + ph);
                g2.setFont(Ui.font(Font.BOLD, 11));
                g2.drawString(m[1] == 0 ? "DETECTED" : "FAILOVER", x + 5, T + 14 + labelRow * 14);
                labelRow = (labelRow + 1) % 2;
            }

            // Silence line
            g2.setStroke(new BasicStroke(2f));
            long[] prev = null;
            for (long[] s : samples) {
                if (prev != null) {
                    g2.setColor(s[2] == 1 ? th.good : th.info);
                    g2.drawLine(xOf(prev[0], start, L, pw), yOf(prev[1], yMax, T, ph),
                                xOf(s[0], start, L, pw), yOf(s[1], yMax, T, ph));
                }
                prev = s;
            }
            g2.dispose();
        }
    }

    // Bar chart of promotion time for each failover
    static class BarChart extends JPanel {
        private final List<String> labels = new ArrayList<>();
        private final List<Long> values = new ArrayList<>();
        private Ui.Theme th;

        BarChart(Ui.Theme t) {
            this.th = t;
            setPreferredSize(new Dimension(360, 280));
        }

        void setTheme(Ui.Theme t) { th = t; repaint(); }

        void add(String label, long value) {
            labels.add(label);
            values.add(value);
            if (labels.size() > 12) {
                labels.remove(0);
                values.remove(0);
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int L = 50, R = 16, T = 16, B = 30;
            int pw = w - L - R, ph = h - T - B;
            g2.setColor(th.surface);
            g2.fillRect(0, 0, w, h);
            if (pw <= 10 || ph <= 10) { g2.dispose(); return; }

            double max = LIMIT;
            for (long v : values) max = Math.max(max, v);
            double yMax = max * 1.15;

            g2.setFont(Ui.font(Font.PLAIN, 11));
            for (int i = 0; i <= 4; i++) {
                int y = T + ph - (int) Math.round(ph * i / 4.0);
                g2.setColor(th.border);
                g2.drawLine(L, y, L + pw, y);
                g2.setColor(th.subText);
                g2.drawString(Math.round(yMax * i / 4.0) + " ms", 6, y + 4);
            }

            if (values.isEmpty()) {
                g2.setFont(Ui.font(Font.PLAIN, 13));
                g2.setColor(th.subText);
                g2.drawString("No failovers yet. Stop the primary to see a recovery.", L + 20, T + ph / 2);
                g2.dispose();
                return;
            }

            int n = values.size();
            int slot = Math.max(pw / Math.max(n, 4), 40);
            int bw = Math.min(56, slot - 16);
            for (int i = 0; i < n; i++) {
                long v = values.get(i);
                int bh = Math.max(2, (int) Math.round(ph * v / yMax));
                int x = L + i * slot + (slot - bw) / 2;
                int y = T + ph - bh;
                g2.setColor(v <= LIMIT ? th.good : th.bad);
                g2.fillRoundRect(x, y, bw, bh, 8, 8);
                g2.setFont(Ui.font(Font.BOLD, 11));
                g2.setColor(th.text);
                g2.drawString(v + " ms", x - 2, y - 5);
                g2.setFont(Ui.font(Font.PLAIN, 11));
                g2.setColor(th.subText);
                g2.drawString(labels.get(i), x + 4, T + ph + 18);
            }

            int ly = T + ph - (int) Math.round(ph * LIMIT / yMax);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                new float[]{6f, 5f}, 0f));
            g2.setColor(th.bad);
            g2.drawLine(L, ly, L + pw, ly);
            g2.dispose();
        }
    }
}