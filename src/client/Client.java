package client;

import common.Constants;
import common.Message;

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Client {
    public static void main(String[] args) throws IOException {
        String name = args[0];
        Socket socket = new Socket("localhost", Constants.PRIMARY_PORT);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

        out.println(name);

        // Background thread: print anything the server sends
        new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    Message m = Message.decode(line);
                    System.out.println("[" + name + "] received: " + m.type + " " + m.payload);
                }
            } catch (IOException ignored) {}
        }).start();

        // Main thread: send trades typed as "receiver:text"
        Scanner sc = new Scanner(System.in);
        while (sc.hasNextLine()) {
            String[] parts = sc.nextLine().split(":", 2);
            if (parts.length == 2) {
                out.println(new Message(Message.Type.MSG, 0, name, parts[0], parts[1]).encode());
            }
        }
        sc.close();
        socket.close();
    }
}