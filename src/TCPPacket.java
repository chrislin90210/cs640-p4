import java.nio.ByteBuffer;
import java.util.Arrays;

public class TCPPacket {

    public static final byte SYN = 0x4;

    public static final byte FIN = 0x2;

    public static final byte ACK = 0x1;

    public static final int TCPHeaderLength = 24;

    private int byteSequenceNumber;

    private int acknowledgement;

    private long timestamp;

    private int length;

    private byte flags;

    private short checksum;

    private byte[] data;

    public void setDataAndLength(byte[] data, int off, int len) {
        this.data = new byte[len];
        System.arraycopy(data, off, this.data, 0, len);
        this.length = len;
    }

    public void setByteSequenceNumber(int byteSequenceNumber) {
        this.byteSequenceNumber = byteSequenceNumber;
    }

    public void setAcknowledgement(int acknowledgement) {
        this.acknowledgement = acknowledgement;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public void setFlags(byte flags) {
        this.flags = flags;
    }

    public short getChecksum() {
        return checksum;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getAcknowledgement() {
        return acknowledgement;
    }

    public int getLength() {
        return length;
    }

    public int getByteSequenceNumber() {
        return byteSequenceNumber;
    }

    public byte getFlags() {
        return flags;
    }

    public int getFullLength() {
        return length + TCPPacket.TCPHeaderLength;
    }

    public byte[] getData() {
        return data;
    }

    public static String formatPacketDet(TCPPacket packet, boolean isData, boolean isToBeSent) {
        String dir = isToBeSent? "snd ": "rcv ";
        String syn = (TCPPacket.SYN & packet.getFlags()) == TCPPacket.SYN ? "S ": "- ";
        String ack = (TCPPacket.ACK & packet.getFlags()) == TCPPacket.ACK ? "S ": "- ";
        String fin = (TCPPacket.FIN & packet.getFlags()) == TCPPacket.FIN ? "S ": "- ";
        String dat = isData ? "D ": "- ";
        double seconds = System.nanoTime() / 1000000000.0;
        String now = String.format("%.6f", seconds);
        return dir + now + syn + ack + fin + dat + packet.getByteSequenceNumber()+ " "+ packet.getLength()+ " "+ packet.getAcknowledgement();
    }

    public byte[] serialize(boolean includeChecksum) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(data.length + 24);
        byteBuffer.putInt(byteSequenceNumber);
        byteBuffer.putInt(acknowledgement);
        byteBuffer.putLong(timestamp);
        byteBuffer.putInt((length << 3) | (flags & 0x7));
        byteBuffer.putShort((short)0);
        if(includeChecksum)
            byteBuffer.putShort(checksum);
        else
            byteBuffer.putShort((short)0);
        byteBuffer.put(data);
        return byteBuffer.array();
    }

    public void deserialize(byte[] serializedPacket) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(serializedPacket);
        byteSequenceNumber = byteBuffer.getInt();
        acknowledgement = byteBuffer.getInt();
        timestamp = byteBuffer.getLong();
        int lengthAndFlags = byteBuffer.getInt();
        length = (lengthAndFlags & 0xfffffff8) >> 3;
        flags = (byte)(lengthAndFlags & 0x7);
        byteBuffer.getShort();
        checksum = byteBuffer.getShort();
        data = new byte[length];
        byteBuffer.get(data, 0, length);
    }

    public short computeChecksum(boolean update) {
        int sum = 0;
        int i;
        byte[] fullPacket = serialize(false);
        for(i = 0; i < fullPacket.length / 2; i++) {
            int chunk = ((fullPacket[2*i] & 0xff) << 8) | (fullPacket[2*i + 1] & 0xff);
            sum += chunk;
            sum = (sum & 0xffff) + (sum >> 16);
        }
        if(fullPacket.length % 2 == 1) {
            int chunk = (fullPacket[fullPacket.length - 1] & 0xff) << 8;
            sum += chunk;
            sum = (sum & 0xffff) + (sum >> 16);
        }
        sum = (sum & 0xffff) + (sum >> 16); // if sum after adding chunk was 0xffff

        short computedChecksum = (short)(~sum & 0xffff);
        if(update)
            checksum = computedChecksum;
        return computedChecksum;
    }

}
