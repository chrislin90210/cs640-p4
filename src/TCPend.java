import java.io.*;
import java.net.InetAddress;
import java.net.Socket;


public class TCPend {

    // TODO: if user enetrs MTU < TCP Header len do not accept
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
            System.out.println("Done writing (all ACKed); will active close now");

            connection.setSocket(outputStream.getSocket());

            System.out.println("Total bytes Wrriten: "+totalBytesWritten);
            // perform active close
            connection.activeEnd(totalBytesWritten - 1); // causes Error: Exception in thread "main" java.net.BindException: Address already in use (Bind failed)

            int[] stat1 = connection.getStats();
            int[] stat2 = outputStream.getStats();
            System.out.println(((stat1[0]+stat2[0]) * 8)/1000000+"Mb "+(stat1[1]+stat2[1])+" "+(stat1[2]+stat2[2])+" "+(stat1[3]+stat2[3])+" "
                    +(stat1[4]+stat2[4])+" "+(stat1[5]+stat2[5]));

        } else {
            System.out.println("Receiver Mode");
            System.out.println("port = " + myPort);
            System.out.println("fileName = " + fileName);
            System.out.println("mtu = " + mtu);
            System.out.println("sws = " + sws);

            //TODO: open connection
            TCPConnection connection = new TCPConnection(myPort, InetAddress.getLocalHost().getHostAddress());
            // perform passive open
            Object[] senderInfo = connection.passiveOpen();
            System.out.println("Got final ACK from server; can read data now");
            // after open, create InputStream
            TCPInputStream inputStream = new TCPInputStream(sws, mtu, myPort, ((InetAddress)senderInfo[1]).getHostAddress(), (int)senderInfo[0]);

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

            connection.setSocket(inputStream.getSocket());
            System.out.println("TCPend: total bytes read: "+totalBytesRead);
            // since we got a -1, it must be because we got a FIN segment
            connection.passiveEnd(totalBytesRead - 1);

            int[] stat1 = connection.getStats();
            int[] stat2 = inputStream.getStats();
            System.out.println(((stat1[0]+stat2[0]) * 8)/1000000+"Mb "+(stat1[1]+stat2[1])+" "+(stat1[2]+stat2[2])+" "+(stat1[3]+stat2[3])+" "
                    +(stat1[4]+stat2[4])+" "+(stat1[5]+stat2[5]));
        }
    }
}