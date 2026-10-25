package common;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;

// Shared colours, fonts and styling helpers for all windows
public final class Ui {
    private Ui() {}

    public static final class Theme {
        public final boolean dark;
        public final Color bg, surface, surfaceAlt, inputBg, border, text, subText,
                           accent, accentText, buy, sell, good, bad, warn, info, purple;

        public Theme(boolean dark) {
            this.dark = dark;
            if (dark) {
                bg = rgb(11, 14, 20);
                surface = rgb(20, 25, 35);
                surfaceAlt = rgb(26, 32, 44);
                inputBg = rgb(30, 37, 50);
                border = rgb(43, 51, 66);
                text = rgb(232, 236, 243);
                subText = rgb(136, 147, 166);
                accent = rgb(59, 130, 246);
                accentText = Color.WHITE;
                buy = rgb(34, 197, 94);
                sell = rgb(239, 68, 68);
                good = rgb(34, 197, 94);
                bad = rgb(248, 113, 113);
                warn = rgb(251, 191, 36);
                info = rgb(96, 165, 250);
                purple = rgb(167, 139, 250);
            } else {
                bg = rgb(241, 244, 248);
                surface = Color.WHITE;
                surfaceAlt = rgb(246, 248, 251);
                inputBg = rgb(250, 251, 253);
                border = rgb(221, 226, 234);
                text = rgb(24, 30, 42);
                subText = rgb(100, 111, 128);
                accent = rgb(37, 99, 235);
                accentText = Color.WHITE;
                buy = rgb(22, 163, 74);
                sell = rgb(220, 38, 38);
                good = rgb(22, 163, 74);
                bad = rgb(220, 38, 38);
                warn = rgb(217, 119, 6);
                info = rgb(37, 99, 235);
                purple = rgb(124, 58, 237);
            }
        }
    }

    public static Color rgb(int r, int g, int b) { return new Color(r, g, b); }

    public static Font font(int style, int size) { return new Font("Segoe UI", style, size); }

    public static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
    }

    public static Border cardBorder(Theme t) {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(t.border, 1, true),
            BorderFactory.createEmptyBorder(14, 16, 14, 16));
    }

    public static void styleField(JTextField f, Theme t) {
        f.setFont(font(Font.PLAIN, 14));
        f.setBackground(t.inputBg);
        f.setForeground(t.text);
        f.setCaretColor(t.text);
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(t.border, 1, true),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)));
    }

    public static void styleButton(AbstractButton b, Color bg, Color fg) {
        b.setFont(font(Font.BOLD, 13));
        b.setBackground(bg);
        b.setForeground(fg);
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    public static void styleTable(JTable tb, Theme t) {
        tb.setRowHeight(30);
        tb.setFont(font(Font.PLAIN, 13));
        tb.setShowGrid(false);
        tb.setIntercellSpacing(new Dimension(0, 0));
        tb.setFillsViewportHeight(true);
        tb.setBackground(t.surface);
        tb.setForeground(t.text);
        tb.setSelectionBackground(t.accent);
        tb.setSelectionForeground(Color.WHITE);
        JTableHeader h = tb.getTableHeader();
        h.setFont(font(Font.BOLD, 12));
        h.setBackground(t.surfaceAlt);
        h.setForeground(t.subText);
        h.setReorderingAllowed(false);
    }

    // Zebra-striped cell renderer that follows the current theme
    public static class Zebra extends DefaultTableCellRenderer {
        private Theme th;

        public Zebra(Theme t) { this.th = t; }

        public void setTheme(Theme t) { this.th = t; }

        protected Color foreground(JTable tb, int row, int col, Theme t) { return t.text; }

        @Override
        public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(tb, v, sel, foc, row, col);
            setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
            setFont(font(Font.PLAIN, 13));
            if (sel) {
                setBackground(tb.getSelectionBackground());
                setForeground(Color.WHITE);
            } else {
                setBackground(row % 2 == 0 ? th.surface : th.surfaceAlt);
                setForeground(foreground(tb, row, col, th));
            }
            return this;
        }
    }
    // Text field that shows grey hint text while it is empty
    public static class HintField extends JTextField {
        private final String hint;

        public HintField(int columns, String hint) {
            super(columns);
            this.hint = hint;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty() && hint != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(getFont());
                g2.setColor(new Color(140, 148, 162));
                Insets in = getInsets();
                FontMetrics fm = g2.getFontMetrics();
                int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(hint, in.left, y);
                g2.dispose();
            }
        }
    }
}