import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.util.concurrent.Semaphore;

public class TCPInputStream extends InputStream {

    private int lastSegRead;

    private int lastByteRead;

    private int nextSegExpected;

    private int nextByteExpected;

    private int lastByteReceived;

    private Segment[] buffer;

    private DatagramSocket socket;

    private InetAddress senderAddress;

    private int senderPort;

    private int slidingWindowSize;

    private DatagramPacket segment;

    private int maxSegmentSize;

    private Semaphore full;

    boolean halfReadSegmentExists;

    private Thread dataHandlerThread;

    private Listener listener;

    private Semaphore mutex;


    private int numIncorrectChkSums;

    private int numRetransmits;

    private int numDupACKS;


    public TCPInputStream(int slidingWindowSize, int MSS, int myPort, String senderIP, int senderPort) throws SocketException, UnknownHostException {
        this.socket = new DatagramSocket(myPort);
        this.senderAddress = InetAddress.getByName(senderIP);
        this.maxSegmentSize = MSS - TCPPacket.TCPHeaderLength;
        this.senderPort = senderPort;
        this.slidingWindowSize = slidingWindowSize;
        this.segment = new DatagramPacket(new byte[MSS], MSS);
        this.buffer = new Segment[slidingWindowSize];
        this.full = new Semaphore(0, true);
        this.mutex = new Semaphore(1, true);
        this.lastByteRead = -1;
        this.lastSegRead = -1;
        this.nextByteExpected = 0;
        this.nextSegExpected = 0;
        this.lastByteReceived = -1;





        this.listener = new Listener();
        this.dataHandlerThread = new Thread(listener);
        this.dataHandlerThread.start();
    }

    @Override
    public int read() throws IOException {
        byte[] temp = new byte[1];
        int ret = read(temp, 0, 1);
        return ret < 0 ? ret : temp[0] & 0xff;
    }

    // TODO: check end of stream signal
    @Override
    public int read(byte[] b, int off, int len)  throws IOException {
        int origLen = len;

        int numChunks = len / maxSegmentSize;
        for(int i = 0; i < numChunks; i++) {
            try {
                full.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            try {
                mutex.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            lastSegRead++;
            Segment dataSegment = buffer[lastSegRead % slidingWindowSize];

            if(dataSegment.isFIN()) {
                // stop the data segment handler

                listener.stop(); // stop looping
                socket.close(); // close socket (for TCPConnection to use a new one for passive end)
                mutex.release();
                return -1;
            }

            lastByteRead += dataSegment.getLength();
            mutex.release();

            int numRead = dataSegment.readAMAP(b, off, maxSegmentSize, 0);

            off += numRead;
            len -= numRead;
        }

        return origLen - len;
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
            if(!segment.getAddress().equals(senderAddress))
                return;
            // check port number
            if(segment.getPort() != senderPort)
                return;

            // convert to tcp format
            TCPPacket tcpPacket = new TCPPacket();
            tcpPacket.deserialize(segment.getData());

            System.out.println(TCPPacket.formatPacketDet(tcpPacket, true, false));

            // compute checksum and discard corrupted segments
            short expectedChecksum = tcpPacket.computeChecksum(false);
            if(expectedChecksum != tcpPacket.getChecksum()) {
                System.out.println("Corrupted Packet: Seq. Num. " + tcpPacket.getByteSequenceNumber());
                sendAck(tcpPacket.getTimestamp());
                return;
            }

            try {
                mutex.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            // if within range
            int roundedUpLBR = ((lastByteRead + maxSegmentSize) / maxSegmentSize) * maxSegmentSize - 1;
            if(nextByteExpected <= tcpPacket.getByteSequenceNumber()
                    && tcpPacket.getByteSequenceNumber() + tcpPacket.getLength() - roundedUpLBR - 1 <= maxSegmentSize * slidingWindowSize) {
                lastByteReceived = Math.max(lastByteReceived, tcpPacket.getByteSequenceNumber() + tcpPacket.getLength() - 1);



                int segNum = tcpPacket.getByteSequenceNumber() / maxSegmentSize;

                if((tcpPacket.getFlags() & TCPPacket.FIN) == TCPPacket.FIN) {
                    segNum = (tcpPacket.getByteSequenceNumber() + maxSegmentSize - 1) / maxSegmentSize;
                    Segment finSegment = new Segment(1, tcpPacket.getByteSequenceNumber());
                    finSegment.setFIN(true);
                    buffer[segNum % slidingWindowSize] = finSegment;
                    full.release();
                    mutex.release();
                    return;
                }


                // if we have already received this packet, skip processing
                if(buffer[segNum % slidingWindowSize]!=null && buffer[segNum % slidingWindowSize].isReceived()) {
                    mutex.release();
                    return;
                }
                // create a data segment
                Segment dataSegment = new Segment(tcpPacket.getLength(), tcpPacket.getByteSequenceNumber());
                // initialize it
                dataSegment.initializeAMAP(tcpPacket.getData(), 0, tcpPacket.getLength());
                // mark that it has been received (and not read yet)
                dataSegment.setReceived();

                buffer[segNum % slidingWindowSize] = dataSegment;

                // accumulate and send acknowledgements
                if(tcpPacket.getByteSequenceNumber() == nextByteExpected) {
                    while(dataSegment!= null && dataSegment.isReceived()) {
                        // data available to be read
                        full.release();
                        // clear the received flag
                        dataSegment.clearReceive();
                        nextByteExpected += dataSegment.getLength();
                        nextSegExpected++;
                        dataSegment = buffer[nextSegExpected % slidingWindowSize];
                    }
                }
                // outside "if"-block to enable fast transmit in sender side
                // but this will cause multiple acks TODO: check
                sendAck(tcpPacket.getTimestamp());

            }
            // if my ACKs didn't reach client so client resent old segments
            else if(tcpPacket.getByteSequenceNumber() < nextByteExpected) {
                sendAck(tcpPacket.getTimestamp());
            }

            mutex.release();
            

    }

    private void sendAck(long timestamp) throws IOException {
        TCPPacket packet = new TCPPacket();
        try {
            mutex.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        packet.setAcknowledgement(nextByteExpected);
        mutex.release();
        packet.setTimestamp(timestamp);
        packet.setFlags(TCPPacket.ACK);
        packet.setByteSequenceNumber(0);
        packet.setDataAndLength(new byte[]{(byte) 0}, 0, 1);
        packet.computeChecksum(true);
        socket.send(new DatagramPacket(packet.serialize(true), 0, packet.getFullLength(), senderAddress, senderPort));
    }
}
