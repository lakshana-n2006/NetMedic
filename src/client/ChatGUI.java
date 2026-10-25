package client;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import common.Constants;
import common.Message;
import common.Ui;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.regex.*;

public class ChatGUI extends JFrame {
    private static final Pattern ORDER = Pattern.compile("(BUY|SELL) (\\d+) (\\S+) @ (\\S+)");
    private static final String[] COLS = {"Time", "Dir", "Side", "Symbol", "Qty", "Price", "Counterparty", "Status"};
    private static final int[] WIDTHS = {80, 90, 60, 80, 60, 90, 130, 110};

    private final String myName;
    private volatile PrintWriter out;
    private Ui.Theme theme = new Ui.Theme(false);
    private final Deque<Integer> pending = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss");

    private final List<JLabel> labels = new ArrayList<>();
    private final List<JTextField> fields = new ArrayList<>();
    private final List<JPanel> cards = new ArrayList<>();

    private final JPanel root = new JPanel(new BorderLayout(16, 16));
    private final JPanel header = new JPanel(new BorderLayout());
    private final JLabel brand = label("TRADING DESK", Ui.font(Font.BOLD, 18));
    private final JLabel status = new JLabel("Connecting...");
    private final JButton themeBtn = new JButton("Dark mode");

    private final JToggleButton buyBtn = new JToggleButton("BUY", true);
    private final JToggleButton sellBtn = new JToggleButton("SELL");
    private final JTextField symbolField = field("e.g. TCS, INFY, AAPL");
    private final JTextField qtyField = field("e.g. 10");
    private final JTextField priceField = field("e.g. 3250.50");
    private final JTextField toField = field("e.g. B");
    private final JLabel notional = label("-", Ui.font(Font.BOLD, 15));
    private final JButton submit = new JButton("SUBMIT BUY ORDER");
    private final JLabel hint = label("Enter the order details and submit.", Ui.font(Font.PLAIN, 11));

    private final DefaultTableModel blotter = new DefaultTableModel(COLS, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable table = new JTable(blotter);
    private Ui.Zebra zebra;

    public ChatGUI(String name) {
        super("Trading Desk - " + name);
        this.myName = name;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1120, 720);
        setMinimumSize(new Dimension(920, 600));
        setLocationRelativeTo(null);
        setContentPane(root);
        root.setBorder(new EmptyBorder(16, 20, 16, 20));

        // ----- Header -----
        JPanel brandBox = new JPanel(new GridLayout(2, 1, 0, 2));
        brandBox.setOpaque(false);
        brandBox.add(brand);
        brandBox.add(label("Signed in as " + name, Ui.font(Font.PLAIN, 12)));

        status.setFont(Ui.font(Font.BOLD, 12));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        right.setOpaque(false);
        right.add(status);
        right.add(themeBtn);
        themeBtn.addActionListener(e -> toggleTheme());

        header.add(brandBox, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        // ----- Order ticket (left) -----
        ButtonGroup group = new ButtonGroup();
        group.add(buyBtn);
        group.add(sellBtn);
        buyBtn.addActionListener(e -> refreshSide());
        sellBtn.addActionListener(e -> refreshSide());

        JPanel sides = new JPanel(new GridLayout(1, 2, 8, 0));
        sides.setOpaque(false);
        sides.add(buyBtn);
        sides.add(sellBtn);

        JPanel top = new JPanel(new BorderLayout(0, 12));
        top.setOpaque(false);
        top.add(label("ORDER TICKET", Ui.font(Font.BOLD, 13)), BorderLayout.NORTH);
        top.add(sides, BorderLayout.CENTER);

        JPanel form = new JPanel(new GridLayout(0, 1, 0, 8));
        form.setOpaque(false);
        form.add(row("SYMBOL", symbolField));
        form.add(row("QUANTITY", qtyField));
        form.add(row("LIMIT PRICE", priceField));
        form.add(row("COUNTERPARTY", toField));

        JPanel est = new JPanel(new BorderLayout(0, 4));
        est.setOpaque(false);
        est.add(label("ESTIMATED VALUE", Ui.font(Font.BOLD, 11)), BorderLayout.NORTH);
        est.add(notional, BorderLayout.CENTER);

        submit.setPreferredSize(new Dimension(0, 42));
        submit.addActionListener(e -> send());
        priceField.addActionListener(e -> send());
        qtyField.addActionListener(e -> send());
        symbolField.addActionListener(e -> send());
        toField.addActionListener(e -> send());

        JPanel body = new JPanel(new BorderLayout(0, 18));
        body.setOpaque(false);
        body.add(top, BorderLayout.NORTH);
        body.add(form, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setOpaque(false);
        bottom.add(est, BorderLayout.NORTH);
        bottom.add(submit, BorderLayout.CENTER);
        bottom.add(hint, BorderLayout.SOUTH);

        JPanel ticket = new JPanel(new BorderLayout(0, 18));
        ticket.setPreferredSize(new Dimension(300, 0));
        ticket.add(body, BorderLayout.CENTER);
        ticket.add(bottom, BorderLayout.SOUTH);
        cards.add(ticket);

        // ----- Blotter (centre) -----
        zebra = new Ui.Zebra(theme) {
            @Override
            protected Color foreground(JTable tb, int row, int col, Ui.Theme t) {
                if (col == 2) {
                    String s = String.valueOf(tb.getValueAt(row, 2));
                    if ("BUY".equals(s)) return t.buy;
                    if ("SELL".equals(s)) return t.sell;
                }
                return t.text;
            }
        };
        table.setDefaultRenderer(Object.class, zebra);
        for (int i = 0; i < WIDTHS.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(WIDTHS[i]);
        }

        JPanel blotterBox = new JPanel(new BorderLayout(0, 12));
        blotterBox.setOpaque(false);
        blotterBox.add(label("TRADE BLOTTER", Ui.font(Font.BOLD, 13)), BorderLayout.NORTH);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        blotterBox.add(tableScroll, BorderLayout.CENTER);

        JPanel blotterCard = new JPanel(new BorderLayout());
        blotterCard.add(blotterBox, BorderLayout.CENTER);
        cards.add(blotterCard);

        JPanel middle = new JPanel(new BorderLayout(16, 0));
        middle.setOpaque(false);
        middle.add(ticket, BorderLayout.WEST);
        middle.add(blotterCard, BorderLayout.CENTER);
        root.add(middle, BorderLayout.CENTER);

        // Live total value as the user types
        DocumentListener dl = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateNotional(); }
            public void removeUpdate(DocumentEvent e) { updateNotional(); }
            public void changedUpdate(DocumentEvent e) { updateNotional(); }
        };
        qtyField.getDocument().addDocumentListener(dl);
        priceField.getDocument().addDocumentListener(dl);

        applyTheme();
        setVisible(true);
        symbolField.requestFocusInWindow();
        new Thread(this::connectLoop).start();
    }

    // ---------- Helpers that build components ----------

    private JLabel label(String text, Font f) {
        JLabel l = new JLabel(text);
        l.setFont(f);
        labels.add(l);
        return l;
    }

    private JTextField field(String hint) {
        Ui.HintField f = new Ui.HintField(10, hint);
        f.setPreferredSize(new Dimension(120, 36));
        fields.add(f);
        return f;
    }

    private JPanel row(String text, JComponent c) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setOpaque(false);
        p.add(label(text, Ui.font(Font.BOLD, 11)), BorderLayout.NORTH);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    // ---------- Order handling ----------

    private void refreshSide() {
        boolean buy = buyBtn.isSelected();
        submit.setText(buy ? "SUBMIT BUY ORDER" : "SUBMIT SELL ORDER");
        Ui.styleButton(submit, buy ? theme.buy : theme.sell, Color.WHITE);
        styleToggle(buyBtn, theme.buy, buy);
        styleToggle(sellBtn, theme.sell, !buy);
    }

    private void styleToggle(JToggleButton b, Color on, boolean selected) {
        Ui.styleButton(b, selected ? on : theme.surfaceAlt, selected ? Color.WHITE : theme.subText);
    }

    private void updateNotional() {
        try {
            double v = Double.parseDouble(qtyField.getText().trim()) * Double.parseDouble(priceField.getText().trim());
            notional.setText(String.format("%,.2f", v));
        } catch (NumberFormatException e) {
            notional.setText("-");
        }
    }

    private void send() {
        String to = toField.getText().trim();
        String sym = symbolField.getText().trim().toUpperCase();
        String qty = qtyField.getText().trim();
        String price = priceField.getText().trim();
        String side = buyBtn.isSelected() ? "BUY" : "SELL";

        if (to.isEmpty() || sym.isEmpty() || qty.isEmpty() || price.isEmpty()) {
            showHint("Complete all ticket fields before submitting.", theme.bad);
            return;
        }
        try {
            if (Integer.parseInt(qty) <= 0 || Double.parseDouble(price) <= 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            showHint("Quantity must be a whole number and price must be positive.", theme.bad);
            return;
        }

        PrintWriter w = out;
        if (w == null) {
            showHint("Desk is reconnecting. Please submit again in a moment.", theme.bad);
            return;
        }

        w.println(new Message(Message.Type.MSG, 0, myName, to, side + " " + qty + " " + sym + " @ " + price).encode());
        addRow("Sent", side, sym, qty, price, to, "Pending");
        qtyField.setText("");
        priceField.setText("");
        showHint("Order submitted.", theme.good);
        qtyField.requestFocusInWindow();
    }

    private void showHint(String text, Color c) {
        hint.setText(text);
        hint.setForeground(c);
    }

    // ---------- Blotter updates (always on the Swing thread) ----------

    private void addRow(String dir, String side, String sym, String qty, String price, String cp, String state) {
        SwingUtilities.invokeLater(() -> {
            blotter.addRow(new Object[]{clock.format(new Date()), dir, side, sym, qty, price, cp, state});
            int row = blotter.getRowCount() - 1;
            if ("Sent".equals(dir)) pending.addLast(row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
        });
    }

    private void acknowledgeOldest() {
        SwingUtilities.invokeLater(() -> {
            Integer row = pending.pollFirst();
            if (row != null && row < blotter.getRowCount()) blotter.setValueAt("Accepted", row, 7);
        });
    }

    private void setStatus(String text, Color c) {
        SwingUtilities.invokeLater(() -> {
            status.setText("\u25CF " + text);
            status.setForeground(c);
        });
    }

    // ---------- Background connection (silent to the user) ----------

    private void connectLoop() {
        while (true) {
            Socket socket = null;
            try {
                socket = new Socket("localhost", Constants.PRIMARY_PORT);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter w = new PrintWriter(socket.getOutputStream(), true);
                w.println(myName);
                out = w;
                setStatus("Online", theme.good);

                String line;
                while ((line = in.readLine()) != null) {
                    Message m = Message.decode(line);
                    if (m.type == Message.Type.MSG) {
                        Matcher mt = ORDER.matcher(m.payload);
                        if (mt.matches()) {
                            addRow("Received", mt.group(1), mt.group(3), mt.group(2), mt.group(4), m.sender, "Open");
                        } else {
                            addRow("Received", "-", "-", "-", "-", m.sender, m.payload);
                        }
                    } else if (m.type == Message.Type.ACK) {
                        acknowledgeOldest();
                    }
                }
            } catch (IOException ignored) {
                // handled below
            } finally {
                out = null;
                if (socket != null) {
                    try { socket.close(); } catch (IOException ignored) {}
                }
            }
            setStatus("Reconnecting...", theme.bad);
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
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
        header.setBackground(t.surface);
        header.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, t.border),
            BorderFactory.createEmptyBorder(14, 20, 14, 20)));

        for (JLabel l : labels) l.setForeground(t.subText);
        brand.setForeground(t.accent);
        notional.setForeground(t.text);

        for (JPanel c : cards) {
            c.setBackground(t.surface);
            c.setBorder(Ui.cardBorder(t));
        }
        for (JTextField f : fields) Ui.styleField(f, t);
        Ui.styleButton(themeBtn, t.surfaceAlt, t.text);

        Ui.styleTable(table, t);
        zebra.setTheme(t);
        table.repaint();

        refreshSide();
        root.repaint();
    }

    public static void openFor(String name) {
        new ChatGUI(name);
    }

    public static void main(String[] args) {
        Ui.installLookAndFeel();
        String name = args.length > 0 ? args[0]
            : JOptionPane.showInputDialog(null, "Enter your client name (e.g. A):", "Login", JOptionPane.QUESTION_MESSAGE);
        if (name == null || name.trim().isEmpty()) System.exit(0);
        final String n = name.trim();
        SwingUtilities.invokeLater(() -> new ChatGUI(n));
    }
}