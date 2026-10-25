package launcher;

import client.ChatGUI;
import common.Ui;
import common.UserStore;
import common.UserStore.User;
import server.DashboardGUI;

import javax.swing.*;
import java.awt.*;

public class LoginGUI extends JFrame {
    private final JTextField userField = new JTextField();
    private final JPasswordField passField = new JPasswordField();
    private final JLabel error = new JLabel(" ");

    public LoginGUI() {
        super("Trading Desk - Sign In");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(480, 600);
        setLocationRelativeTo(null);
        setResizable(false);

        Ui.Theme t = new Ui.Theme(false);

        // Gradient background
        JPanel bg = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, new Color(9, 14, 28), getWidth(), getHeight(), new Color(29, 78, 216)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        setContentPane(bg);

        // White login card
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(t.surface);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(t.border, 1, true),
            BorderFactory.createEmptyBorder(32, 36, 32, 36)));
        card.setPreferredSize(new Dimension(390, 470));

        JLabel brand = new JLabel("TRADING DESK");
        brand.setFont(Ui.font(Font.BOLD, 24));
        brand.setForeground(t.accent);
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel sub = new JLabel("Sign in to your trading account");
        sub.setFont(Ui.font(Font.PLAIN, 13));
        sub.setForeground(t.subText);
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);

        Ui.styleField(userField, t);
        Ui.styleField(passField, t);
        userField.setAlignmentX(Component.LEFT_ALIGNMENT);
        passField.setAlignmentX(Component.LEFT_ALIGNMENT);
        userField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        passField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        JButton login = new JButton("SIGN IN");
        Ui.styleButton(login, t.accent, Color.WHITE);
        login.setAlignmentX(Component.LEFT_ALIGNMENT);
        login.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));

        error.setFont(Ui.font(Font.PLAIN, 12));
        error.setForeground(t.bad);
        error.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel foot = new JLabel("Clients trade. Administrators monitor the platform.");
        foot.setFont(Ui.font(Font.PLAIN, 11));
        foot.setForeground(t.subText);
        foot.setAlignmentX(Component.LEFT_ALIGNMENT);

        card.add(brand);
        card.add(Box.createVerticalStrut(4));
        card.add(sub);
        card.add(Box.createVerticalStrut(28));
        card.add(fieldLabel("USERNAME"));
        card.add(Box.createVerticalStrut(6));
        card.add(userField);
        card.add(Box.createVerticalStrut(16));
        card.add(fieldLabel("PASSWORD"));
        card.add(Box.createVerticalStrut(6));
        card.add(passField);
        card.add(Box.createVerticalStrut(10));
        card.add(error);
        card.add(Box.createVerticalStrut(12));
        card.add(login);
        card.add(Box.createVerticalStrut(20));
        card.add(foot);

        bg.add(card);

        login.addActionListener(e -> attemptLogin());
        passField.addActionListener(e -> attemptLogin());
        setVisible(true);
        userField.requestFocusInWindow();
    }

    private static JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.font(Font.BOLD, 11));
        l.setForeground(new Color(100, 111, 128));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private void attemptLogin() {
        String name = userField.getText().trim();
        String pass = new String(passField.getPassword());
        User user = UserStore.login(name, pass);
        if (user == null) {
            error.setText("Invalid username or password.");
            passField.setText("");
            return;
        }
        dispose();
        if (user.role == UserStore.Role.ADMIN) {
            new DashboardGUI();
        } else {
            ChatGUI.openFor(user.name);
        }
    }

    public static void main(String[] args) {
        Ui.installLookAndFeel();
        SwingUtilities.invokeLater(LoginGUI::new);
    }
}