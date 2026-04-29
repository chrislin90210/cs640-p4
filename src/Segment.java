import java.util.Arrays;

public class Segment {

    private final int startingByteSeqNum;
    public byte[] data;
    private int length;
    private boolean received; // for receiver side logic

    private boolean isFIN;

    public Segment(int segmentSize, int startingByteSeqNum) {
        this.startingByteSeqNum = startingByteSeqNum;
        this.data = new byte[segmentSize];
        this.length = 0;
        this.received = false;
        this.isFIN = false;
    }

    public void initialize(byte[] source, int off, int len) {
        for(int i = off; i < len; i++)
            data[i-off] = source[i];
        length = len;
    }

    public int initializeAMAP(byte[] source, int off, int len) {
        int origLen = length;
        for(int i = 0; i < len - off; i++) {
            if(length == data.length)
                return i;
            data[length++] = source[off + i];
        }
        return length - origLen;
    }

    public int readAMAP(byte[] dest, int off, int len, int from) {
        int i;
        for(i = 0; i < Math.min(len, length - from); i++) {
            dest[off + i] = data[from + i];
        }
        return i;
    }

    public boolean isFull() {
        return length == data.length;
    }

    public int getLength() {
        return length;
    }

    public void setReceived() {
        this.received = true;
    }

    public boolean isReceived() {
        return received;
    }

    public void clearReceive() {
        this.received = false;
    }

    public boolean isFIN() {
        return isFIN;
    }

    public void setFIN(boolean flag) {
        isFIN = flag;
    }
    public int getStartingByteSeqNum() {
        return startingByteSeqNum;
    }
}
