import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TCPConnection {

    private InetAddress toAddress;
    private int toPort;

    private final InetAddress myAddress;

    private final int myPort;

    private DatagramSocket socket;

    private ScheduledExecutorService scheduler;

    private ScheduledFuture<?> timer;

    private final long T0 = TimeUnit.SECONDS.toNanos(5);

    private int numTries;

    private boolean isActive;

    private boolean isReadyToClose;

    private Listener listener;

    private int expectedFINAck;

    private int numIncorrectChkSums;

    private int numRetransmissions;

    public void setSocket(DatagramSocket socket) {
        this.socket = socket;
    }

    public int[] getStats() {
        return new int[]{0, 0, 0, numIncorrectChkSums, numRetransmissions, 0};
    }

    public TCPConnection(int myPort, String myIP) throws UnknownHostException, SocketException {
        this.myPort = myPort;
        this.myAddress = InetAddress.getByName(myIP);
        this.socket = new DatagramSocket(myPort);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.numTries = 0;
        this.isActive = false;
        this.expectedFINAck = -1;
        this.isReadyToClose = false;
        this.lastByteRead = 0; // for server

        numIncorrectChkSums=0;

        numRetransmissions=0;
    }

    public void activeOpen(int toPort, String toIP) throws IOException, InterruptedException {
        this.isActive = true;
        this.toPort = toPort;
        this.toAddress = InetAddress.getByName(toIP);
        listener = new Listener();
        Thread listenerThread = new Thread(listener);

        // perform active open
        sendSYN();
        // start listening for responses
        listenerThread.start();

        listenerThread.join();

        // done with socket
        if(timer!=null)
            timer.cancel(false);

        socket.close();
    }

    public Object[] passiveOpen() throws InterruptedException {
        listener = new Listener();
        Thread listenerThread = new Thread(listener);
        // start listening for responses
        listenerThread.start();

        listenerThread.join();
        numTries = 0;
        return new Object[]{toPort, toAddress};
    }

    public void activeEnd(int lastByteSent) throws IOException, InterruptedException {
        isReadyToClose = true;
        TCPPacket packet = new TCPPacket();
        packet.setDataAndLength(new byte[]{0}, 0, 1);
        packet.setFlags(TCPPacket.FIN);
        packet.setByteSequenceNumber(lastByteSent + 1);
        this.expectedFINAck = lastByteSent + 2;
        packet.setAcknowledgement(1);
        packet.setTimestamp(System.nanoTime());
        packet.computeChecksum(true);
        socket.send(new DatagramPacket(packet.serialize(true), 0, packet.getFullLength(), toAddress, toPort));
        System.out.println(TCPPacket.formatPacketDet(packet, false, true));
        this.timer = scheduler.schedule(() -> {
            try {
                resendFIN(packet);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, T0, TimeUnit.NANOSECONDS);
        listener = new Listener();
        Thread listenerThread = new Thread(listener);
        // start listening for responses
        listenerThread.start();

        listenerThread.join();
        if(timer!=null)
            timer.cancel(false);
        socket.close();
    }

    public int lastByteRead;

    public void passiveEnd(int lastByteRead) throws IOException, InterruptedException {
        this.lastByteRead = lastByteRead;
        isReadyToClose = true;
        TCPPacket packet = new TCPPacket();
        packet.setDataAndLength(new byte[]{0}, 0, 1);
        packet.setFlags((byte) ((TCPPacket.ACK | TCPPacket.FIN) & 0xff));
        packet.setByteSequenceNumber(1);
        packet.setAcknowledgement(lastByteRead + 2);
        packet.setTimestamp(System.nanoTime());
        packet.computeChecksum(true);
        socket.send(new DatagramPacket(packet.serialize(true), 0, packet.getFullLength(), toAddress, toPort));
        System.out.println(TCPPacket.formatPacketDet(packet, false, true));
        this.timer = scheduler.schedule(() -> {
            try {
                resendFIN(packet);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, T0, TimeUnit.NANOSECONDS);
        listener = new Listener();
        Thread listenerThread = new Thread(listener);
        // start listening for responses
        listenerThread.start();

        listenerThread.join();
        if(timer!=null)
            timer.cancel(false);
        socket.close();
    }



    private class Listener implements Runnable {
        private volatile boolean running = true;

        @Override
        public void run() {
            while(running) {
                try {
                    receive();
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        public void stop() {
            running = false;
        }
    }

    private void sendSYN() throws IOException {
        TCPPacket packet = new TCPPacket();
        packet.setByteSequenceNumber(0);
        packet.setDataAndLength(new byte[]{0}, 0, 1);
        packet.setFlags(TCPPacket.SYN);
        packet.setTimestamp(System.nanoTime());
        packet.computeChecksum(true);
        socket.send(new DatagramPacket(packet.serialize(true), 0, packet.getFullLength(), toAddress, toPort));
        System.out.println(TCPPacket.formatPacketDet(packet, false, true));
        this.timer = scheduler.schedule(() -> {
            try {
                resendSYN(packet);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, T0, TimeUnit.NANOSECONDS);
    }


    // TODO: check edge case: many tries no response
    private void resendSYN(TCPPacket packet) throws IOException {
        numRetransmissions++;
        numTries ++;
        if(numTries == 16) {
            timer.cancel(false);
            return;
        }
        socket.send(new DatagramPacket(packet.serialize(true), 0, packet.getFullLength(), toAddress, toPort));
        System.out.println(TCPPacket.formatPacketDet(packet, false, true));
        timer = scheduler.schedule(() -> {
            try {
                resendSYN(packet);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, T0, TimeUnit.NANOSECONDS);
    }



    private void resendFIN(TCPPacket packet) throws IOException {
        numRetransmissions++;
        numTries ++;
        if(numTries == 16)
            timer.cancel(false);
        socket.send(new DatagramPacket(packet.serialize(true), 0, packet.getFullLength(), toAddress, toPort));
        System.out.println(TCPPacket.formatPacketDet(packet, false, true));
        timer = scheduler.schedule(() -> {
            try {
                resendFIN(packet);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, T0, TimeUnit.NANOSECONDS);
    }

    // TODO: wait TIME_OUT time for response

    public void receive() throws IOException, InterruptedException {
        DatagramPacket segment = new DatagramPacket(new byte[50], 50);

        try {
            socket.receive(segment);
        } catch (SocketTimeoutException ex) {
            // can close now
            listener.stop();
            if(timer!=null)
                timer.cancel(false);
            socket.close();
            return;
        } catch (RuntimeException ex) {
            if(socket.isClosed()) {
                listener.stop();
                if(timer!=null)
                    timer.cancel(false);
                return;
            }
        }

        if(isActive) {
            // check IP address
            if (!segment.getAddress().equals(toAddress))
                return;
            // check port number
            if (segment.getPort() != toPort)
                return;
        }
        // convert to tcp format
        TCPPacket tcpPacket = new TCPPacket();
        tcpPacket.deserialize(Arrays.copyOf(segment.getData(), segment.getLength()));

        System.out.println(TCPPacket.formatPacketDet(tcpPacket, false, false));

        // compute checksum and discard corrupted segments
        short expectedChecksum = tcpPacket.computeChecksum(false);
        if(expectedChecksum != tcpPacket.getChecksum()) {
            numIncorrectChkSums++;
            System.out.println("Corrupted Packet: Seq. Num. " + tcpPacket.getByteSequenceNumber());
            return;
        }

        if((tcpPacket.getFlags() & TCPPacket.ACK) == TCPPacket.ACK) {
            // server sent this
            if((tcpPacket.getFlags() & TCPPacket.SYN) == TCPPacket.SYN && isActive) {
                if(tcpPacket.getAcknowledgement() != 1)
                    return;
                System.out.println("Got SYN-ACK from server");
                timer.cancel(false);
                numTries=0;
                TCPPacket packet = new TCPPacket();
                packet.setDataAndLength(new byte[]{0}, 0, 1);
                packet.setFlags(TCPPacket.ACK);
                packet.setAcknowledgement(1);
                packet.setTimestamp(System.nanoTime());
                packet.computeChecksum(true);
                socket.send(new DatagramPacket(packet.serialize(true), 0, packet.getFullLength(), toAddress, toPort));
                System.out.println(TCPPacket.formatPacketDet(packet, false, true));

                // TODO: check

                listener.stop();
                if(timer!=null)
                    timer.cancel(false);
                socket.close();
                // if my ack doesn't reach server, I signal ack on first data segment

                // TODO: start output stream


            }
            // server sent this
            else if((tcpPacket.getFlags() & TCPPacket.FIN) == TCPPacket.FIN && isActive) {
                System.out.println("Expected FIN ACK :" + expectedFINAck);
                if(tcpPacket.getAcknowledgement() != expectedFINAck)
                    return;
                System.out.println("Got FIN-ACK from server");
                timer.cancel(false);
                numTries=0;
                TCPPacket packet = new TCPPacket();
                packet.setDataAndLength(new byte[]{0}, 0, 1);
                packet.setFlags(TCPPacket.ACK);
                packet.setAcknowledgement(2);
                packet.setTimestamp(System.nanoTime());
                packet.computeChecksum(true);
                socket.send(new DatagramPacket(packet.serialize(true), 0, packet.getFullLength(), toAddress, toPort));
                System.out.println(TCPPacket.formatPacketDet(packet, false, true));

                // TODO: wait 2 times MAX timeout seconds seconds before closing
                socket.setSoTimeout(10); // TODO: change actual value
                return;

            }
            else if(isReadyToClose) {
                if(tcpPacket.getAcknowledgement() != 2)
                    return;

                timer.cancel(false);
                // TODO: handle this
                listener.stop();
                socket.close();
            }
            // client sent this
            else if(isActive) {
                System.out.println("Got ACK from client after my SYN-ACK");
                timer.cancel(false);

                // TODO: check

                listener.stop();
                socket.close();
            }


        }

        // client has sent me connection request
        else if((tcpPacket.getFlags() & TCPPacket.SYN) == TCPPacket.SYN) {
            System.out.println("Got SYN from client");
            isActive = true;
            toAddress = segment.getAddress();
            toPort = segment.getPort();
            TCPPacket packet = new TCPPacket();
            packet.setDataAndLength(new byte[]{0}, 0, 1);
            packet.setFlags((byte) ((TCPPacket.ACK | TCPPacket.SYN) & 0xff));
            packet.setByteSequenceNumber(0);
            packet.setAcknowledgement(1);
            packet.setTimestamp(System.nanoTime());
            packet.computeChecksum(true);
            socket.send(new DatagramPacket(packet.serialize(true), 0, packet.getFullLength(), toAddress, toPort));
            System.out.println("My reply: flags are "+packet.getFlags());
            System.out.println(TCPPacket.formatPacketDet(packet, false, true));
            if(this.timer != null)
                this.timer.cancel(false);
            this.timer = scheduler.schedule(() -> {
                try {
                    resendSYN(packet);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }, T0, TimeUnit.NANOSECONDS);
        }

        // TODO: else if FIN? (maybe client resent FIN because my FIN+ACK didnt reach it
        else if((tcpPacket.getFlags() & TCPPacket.FIN) == TCPPacket.FIN) {
            timer.cancel(false);
            passiveEnd(this.lastByteRead);
        }


    }






}
