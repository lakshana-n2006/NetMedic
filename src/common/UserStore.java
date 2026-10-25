package common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
public class UserStore {
    public enum Role { CLIENT, ADMIN }

    public static class User {
        public final String name;
        public final Role role;
        public User(String name, Role role) { this.name = name; this.role = role; }
    }

    private static final String FILE = "users.txt";

    // Returns the user if the name and password match, otherwise null
    public static User login(String name, String password) {
        String wanted = hash(password);
        try (BufferedReader r = new BufferedReader(new FileReader(FILE))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(":");
                if (p.length == 3 && p[0].equals(name) && p[2].equals(wanted)) {
                    return new User(p[0], Role.valueOf(p[1]));
                }
            }
        } catch (IOException e) {
            System.err.println("Cannot read " + FILE + ": " + e.getMessage());
        }
        return null;
    }

    public static String hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Helper: prints the hash of each demo password
    public static void main(String[] args) {
        System.out.println("admin123 -> " + hash("admin123"));
        System.out.println("pass1    -> " + hash("pass1"));
        System.out.println("pass2    -> " + hash("pass2"));
    }
}