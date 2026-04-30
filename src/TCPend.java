import java.io.*;
import java.net.InetAddress;


public class TCPend {

    public static void main(String[] args) throws Exception {

        int myPort = 0, remotePort = 0, mtu = 0, sws = 0;
        String remoteIP = null, fileName = null;

        boolean senderMode = false;

        for (int i = 0; i < args.length; i++) {

            switch (args[i]) {
                case "-p":
                    myPort = Integer.parseInt(args[++i]);
                    break;

                case "-s":
                    remoteIP = args[++i];
                    senderMode = true;
                    break;

                case "-a":
                    remotePort = Integer.parseInt(args[++i]);
                    break;

                case "-f":
                    fileName = args[++i];
                    break;

                case "-m":
                    mtu = Integer.parseInt(args[++i]);
                    break;

                case "-c":
                    sws = Integer.parseInt(args[++i]);
                    break;
            }
        }

        if (senderMode) {
            System.out.println("Sender Mode");
            System.out.println("myPort = " + myPort);
            System.out.println("remoteIP = " + remoteIP);
            System.out.println("remotePort = " + remotePort);
            System.out.println("fileName = " + fileName);
            System.out.println("mtu = " + mtu);
            System.out.println("sws = " + sws);
            // TODO: open connection

            TCPConnection connection = new TCPConnection(myPort, InetAddress.getLocalHost().getHostAddress());
            // perform active open
            connection.activeOpen(remotePort, remoteIP);
            // after open, create OutputStream
            TCPOutputStream outputStream = new TCPOutputStream(sws, mtu, myPort, remoteIP, remotePort);

            // write everything
            assert fileName != null;
            int totalBytesWritten = 0;
            try (FileInputStream fis = new FileInputStream(fileName)) {
                byte[] buffer = new byte[mtu - TCPPacket.TCPHeaderLength];
                int bytesRead;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytesWritten += bytesRead;
                }
                outputStream.flush();
                // wait till everything is written successfully
                outputStream.waitTillDone(totalBytesWritten - 1);

            } catch (IOException e) {
                e.printStackTrace();
            }

            // perform active close
            connection.activeEnd(totalBytesWritten - 1);

        } else {
            System.out.println("Receiver Mode");
            System.out.println("port = " + myPort);
            System.out.println("fileName = " + fileName);
            System.out.println("mtu = " + mtu);
            System.out.println("sws = " + sws);

            //TODO: open connection
            TCPConnection connection = new TCPConnection(myPort, InetAddress.getLocalHost().getHostAddress());
            // perform passive open
            connection.passiveOpen();
            System.out.println("Got final ACK from server; cant read data now");
            // after open, create InputStream
            TCPInputStream inputStream = new TCPInputStream(sws, mtu, myPort, remoteIP, remotePort);

            assert fileName != null;
            int totalBytesRead = 0;
            try(FileOutputStream fos = new FileOutputStream(fileName)) {
                byte[] buffer = new byte[mtu - TCPPacket.TCPHeaderLength];
                int bytesRead;

                while ((bytesRead = inputStream.read(buffer, 0, buffer.length)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                }
                fos.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }

            // since we got a -1, it must be because we got a FIN segment
            connection.passiveEnd(totalBytesRead);
        }
    }
}