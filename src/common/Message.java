package common;

public class Message {
    public enum Type { MSG, ACK, HB, FILE_OFFER, NEW_PRIMARY, SYNC }

    public final Type type;
    public final long seq;
    public final String sender;
    public final String receiver;
    public final String payload;

    public Message(Type type, long seq, String sender, String receiver, String payload) {
        this.type = type;
        this.seq = seq;
        this.sender = sender;
        this.receiver = receiver;
        this.payload = payload;
    }

    // Converts the message to one text line for sending over the socket
    public String encode() {
        return type + "|" + seq + "|" + sender + "|" + receiver + "|" + payload;
    }

    // Converts one text line back into a Message
    public static Message decode(String line) {
        String[] parts = line.split("\\|", 5);
        return new Message(
            Type.valueOf(parts[0]),
            Long.parseLong(parts[1]),
            parts[2],
            parts[3],
            parts.length > 4 ? parts[4] : ""
        );
    }
}