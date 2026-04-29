import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;

public class TCPOutputStream extends OutputStream {

    private int slidingWindowSize;

    private int maxSegmentSize;

    private int lastByteSent;

    private int lastSegSent;

    private int lastByteAcked;

    private int lastSegAcked;

    private int lastSegWritten;


    private int lastByteWritten;

    private int rep_counter;

    boolean unfilledSegmentExists;

    private Semaphore available;

    private Segment[] buffer;

    private DatagramSocket socket;

    private DatagramPacket segment;

    private InetAddress receiverAddress;

    private int receiverPort;

    private ScheduledFuture<?>[] timers;

    private ScheduledExecutorService scheduler;

    private boolean firstAck;

    private double T0;
    private double ERTT;
    private double EDEV;

    private Thread ackHandlerThread;

    private Semaphore done;

    private Semaphore mutex;

    private int lastExpectedByte;

    private Listener listener;

    private int numIncorrectChkSums;

    private int numRetransmits;

    private int numDupACKS;



    public TCPOutputStream(int slidingWindowSize, int MSS, int myPort, String receiverIP, int receiverPort) throws Exception {
        if(MSS < TCPPacket.TCPHeaderLength)
            throw new Exception("MSS cannot be smaller than the header length of TCP (24)");
        this.slidingWindowSize = slidingWindowSize;
        this.maxSegmentSize = MSS - TCPPacket.TCPHeaderLength;
        this.buffer = new Segment[slidingWindowSize];
        this.socket = new DatagramSocket(myPort);
        this.segment = new DatagramPacket(new byte[MSS], MSS);
        this.available = new Semaphore(slidingWindowSize, true);
        this.unfilledSegmentExists = false;
        this.rep_counter = 0;
        this.lastByteAcked = -1;
        this.lastSegAcked = -1;
        this.lastByteSent = -1;
        this.lastSegSent = -1;
        this.lastSegWritten = -1;
        this.lastByteWritten = -1;
        this.lastExpectedByte = -2;
        this.timers = new ScheduledFuture<?>[slidingWindowSize];
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.T0 = TimeUnit.SECONDS.toNanos(5);
        this.firstAck = true;
        this.done = new Semaphore(0, true);
        this.receiverAddress = InetAddress.getByName(receiverIP);
        this.receiverPort = receiverPort;
        this.mutex = new Semaphore(1, true);


        numIncorrectChkSums = 0;

        numRetransmits = 0;

        numDupACKS = 0;


        this.listener = new Listener();
        this.ackHandlerThread = new Thread(listener);
        this.ackHandlerThread.start();
    }


    @Override
    public void write(int b) throws IOException {
        // only low 8 bits are written
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] source, int off, int len) throws IOException {

        try {
            mutex.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if(unfilledSegmentExists) {
            Segment unfilledSeg = buffer[lastSegWritten % slidingWindowSize];
            int numBytesFilled = unfilledSeg.initializeAMAP(source, off, len);
            off += numBytesFilled;
            len -= numBytesFilled;
            lastByteWritten += numBytesFilled;
            if(unfilledSeg.isFull()) {
                // TODO: check
                sendSegment(lastSegWritten);
                startTimerFor(lastSegWritten);
                lastSegSent = lastSegWritten;
                lastByteSent = lastByteWritten;
                unfilledSegmentExists = false;
            }
        }
        mutex.release();

        int numChunks = len / maxSegmentSize;
        for(int i = 0; i < numChunks; i++) {
            try {
                available.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            try {
                mutex.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            // the first byte of this new segment is lastByteWritten + 1
            Segment newSegment = new Segment(maxSegmentSize, lastByteWritten + 1);
            // new segment written
            lastSegWritten++;
            // new maxSegmentSize bytes written
            lastByteWritten += maxSegmentSize;
            // load the data into the buffer
            newSegment.initialize(source, off + i * maxSegmentSize, maxSegmentSize);
            buffer[lastSegWritten % slidingWindowSize] = newSegment;
            // TODO: check
            sendSegment(lastSegWritten);
            startTimerFor(lastSegWritten);
            lastSegSent = lastSegWritten;
            lastByteSent = lastByteWritten;
            mutex.release();
        }
        off += numChunks * maxSegmentSize;
        len -= numChunks * maxSegmentSize;
        if(len > 0) {
            try {
                available.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            try {
                mutex.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            Segment newUnfilledSeg = new Segment(maxSegmentSize, lastByteWritten + 1);
            // new segment created
            lastSegWritten++;
            // some of it is filled
            lastByteWritten += len;
            newUnfilledSeg.initialize(source, off, len);
            buffer[lastSegWritten % slidingWindowSize] = newUnfilledSeg;
            unfilledSegmentExists = true;
            mutex.release();
            // Do not send segment yet
        }
    }

    /****
     * We assume that the Application layer can only flush when it has no more data to send.
     * That is, it can flush only when the next operation it invokes is a close (FYN)
     */
    @Override
    public void flush() throws IOException {
        try {
            mutex.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if(unfilledSegmentExists) {
            sendSegment(lastSegWritten);
            startTimerFor(lastSegWritten);
            lastByteSent = lastByteWritten;
            lastSegSent = lastSegWritten;
        }
        mutex.release();
    }

    public void waitTillDone(int lastExpectedByte) throws InterruptedException {
        try {
            mutex.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if(lastByteAcked == lastExpectedByte)
            return;
        this.lastExpectedByte = lastExpectedByte;
        mutex.release();
        // wait till all segments are ACKED
        done.acquire();
        // once ACKED, stop listening

        listener.stop();
        socket.close();

    }



    private void handleTimeout(int segNum) {
        try {
            try {
                mutex.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            numRetransmits++;
            mutex.release();
            sendSegment(segNum);
        } catch (IOException ex) {
            System.out.println("IO Exception in resending");
        }
        // TODO: reset timers
        startTimerFor(segNum);

    }

    private void startTimerFor(int segNum) {
        // cancel old timer if reused slot
        ScheduledFuture<?> old = timers[segNum % slidingWindowSize];
        if(old != null)
            old.cancel(false);

        timers[segNum % slidingWindowSize] = scheduler.schedule(() -> handleTimeout(segNum), (long)T0, TimeUnit.NANOSECONDS);
    }




    private void sendSegment(int segNum) throws IOException {
        Segment seg = buffer[segNum % slidingWindowSize];
        TCPPacket packet = new TCPPacket();
        packet.setFlags(TCPPacket.ACK);
        packet.setAcknowledgement(1);
        packet.setDataAndLength(seg.data, 0, seg.getLength());
        packet.setByteSequenceNumber(seg.getStartingByteSeqNum());
        packet.setTimestamp(System.nanoTime());
        packet.computeChecksum(true);
        socket.send(new DatagramPacket(packet.serialize(true), 0, packet.getFullLength(), receiverAddress, receiverPort));
        System.out.println(TCPPacket.formatPacketDet(packet, true, true));
    }


    private class Listener implements Runnable {
        private volatile boolean running = true;

        @Override
        public void run() {
            while(running) {
                try {
                    handlePacket();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        public void stop() {
            running = false;
        }
    }

    private void handlePacket() throws IOException {

            socket.receive(segment);
            // check IP address
            if(!segment.getAddress().equals(receiverAddress))
                return;
            // check port number
            if(segment.getPort() != receiverPort)
                return;

            // convert to tcp format
            TCPPacket tcpPacket = new TCPPacket();
            tcpPacket.deserialize(Arrays.copyOf(segment.getData(), segment.getLength()));

            // compute checksum and discard corrupted segments
            short expectedChecksum = tcpPacket.computeChecksum(false);
            if(expectedChecksum != tcpPacket.getChecksum()) {
                try {
                    mutex.acquire();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                numIncorrectChkSums++;
                mutex.release();
                System.out.println("Corrupted Packet: Seq. Num. " + tcpPacket.getByteSequenceNumber());
                return;
            }

            try {
                mutex.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if((tcpPacket.getFlags() & TCPPacket.ACK) == TCPPacket.ACK) {

                 // If I (client) sent ACK for server's SYN+ACK but my ACK didn't reach it,
                 // server could resend SYN+ACK; this is not data segment, so ignore

                if((tcpPacket.getFlags() & TCPPacket.SYN) == TCPPacket.SYN) {
                    mutex.release();
                    return;
                }


                if(lastByteAcked + 1 < tcpPacket.getAcknowledgement() && tcpPacket.getAcknowledgement() <= lastByteSent + 1) {
                    rep_counter = 0;
                    do {
                        lastSegAcked++;
                        // clear timer of LS
                        timers[lastSegAcked].cancel(false);

                        lastByteAcked += buffer[lastSegAcked % slidingWindowSize].getLength();
                        available.release();
                    } while(lastByteAcked < tcpPacket.getAcknowledgement() - 1);

                    if(lastByteAcked == lastExpectedByte)
                        done.release();


                    long currentTime = System.nanoTime();
                    // compute RTT estimates
                    if(firstAck) {
                        ERTT = currentTime - tcpPacket.getTimestamp();
                        EDEV = 0;
                        T0 = 2.0 * ERTT;
                        firstAck = false;
                    }
                    else {
                        double SRTT = currentTime - tcpPacket.getTimestamp();
                        double SDEV = Math.abs(SRTT - ERTT);
                        double a = 0.875;
                        ERTT = a * ERTT + (1 - a) * SRTT;
                        double b = 0.75;
                        EDEV = b * EDEV + (1 - b) * SDEV;
                        T0 = ERTT + 4.0 * EDEV;
                    }


                }
                // TODO: check
                else if(lastByteAcked + 1 == tcpPacket.getAcknowledgement()) {
                    rep_counter++;
                    numDupACKS++;
                    if(rep_counter >= 3) {
                        if(rep_counter == 16) {
                            System.out.println("Error: Maximum Retransmissions crossed");
                            listener.stop();
                            socket.close();
                            return;
                        }
                        for(int i = lastSegAcked + 1; i <= lastSegSent; i++) {
                            sendSegment(i);
                            startTimerFor(i);
                        }
                    }

                }
            }
            mutex.release();

    }
}
