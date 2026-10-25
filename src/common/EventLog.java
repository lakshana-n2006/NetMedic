package common;

import java.io.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

// Writes one line per event to logs/events.log, read by the dashboard
// Format: TIME|TYPE|DETAILS   e.g. 10:15:02.118|HB|primary alive
public class EventLog {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static synchronized void log(String type, String details) {
        String line = LocalTime.now().format(FMT) + "|" + type + "|" + details;
        try (PrintWriter w = new PrintWriter(new FileWriter("logs/events.log", true), true)) {
            w.println(line);
        } catch (IOException e) {
            System.err.println("Event log failed: " + e.getMessage());
        }
    }
}