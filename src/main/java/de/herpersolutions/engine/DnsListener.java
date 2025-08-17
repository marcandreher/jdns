package de.herpersolutions.engine;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.xbill.DNS.Flags;
import org.xbill.DNS.Header;
import org.xbill.DNS.Message;

public class DnsListener {
     private final int port;
        private final AuthoritativeEngine engine;
        private volatile boolean running = true;
        private DatagramSocket udpSocket;
        private ServerSocket tcpSocket;
        private final ExecutorService pool = Executors.newCachedThreadPool();

        public DnsListener(int port, AuthoritativeEngine engine) { this.port = port; this.engine = engine; }

        public void run() throws IOException {
            udpSocket = new DatagramSocket(port);
            tcpSocket = new ServerSocket(port);

            pool.submit(this::udpLoop);
            pool.submit(this::tcpLoop);

            // Block the main thread
            try { while (running) Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }

        public void close() {
            running = false;
            try { if (udpSocket != null) udpSocket.close(); } catch (Exception ignored) {}
            try { if (tcpSocket != null) tcpSocket.close(); } catch (Exception ignored) {}
            pool.shutdownNow();
        }

        private void udpLoop() {
            byte[] buf = new byte[4096];
            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(packet);

                    byte[] in = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset()+packet.getLength());
                    Message query = new Message(in);
                    Message resp = engine.answer(query);

                    byte[] out = resp.toWire();
                    if (out.length > 512) {
                        // Truncate for UDP per RFC 1035 and set TC flag
                        Header h = resp.getHeader();
                        h.setFlag(Flags.TC);
                        out = trimTo512(out);
                    }
                    DatagramPacket reply = new DatagramPacket(out, out.length, packet.getAddress(), packet.getPort());
                    udpSocket.send(reply);
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        private byte[] trimTo512(byte[] data) {
            if (data.length <= 512) return data;
            return Arrays.copyOf(data, 512);
        }

        private void tcpLoop() {
            while (running) {
                try {
                    Socket s = tcpSocket.accept();
                    pool.submit(() -> handleTcp(s));
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        }

        private void handleTcp(Socket s) {
            try (DataInputStream in = new DataInputStream(s.getInputStream());
                 DataOutputStream out = new DataOutputStream(s.getOutputStream())) {
                while (running && !s.isClosed()) {
                    // TCP DNS: 2-byte length prefix
                    int len;
                    try { len = in.readUnsignedShort(); } catch (EOFException eof) { break; }
                    byte[] msg = in.readNBytes(len);
                    Message query = new Message(msg);
                    Message resp = engine.answer(query);
                    byte[] wire = resp.toWire();
                    out.writeShort(wire.length);
                    out.write(wire);
                    out.flush();
                }
            } catch (IOException ignored) {
            } finally {
                try { s.close(); } catch (IOException ignored) {}
            }
        }
}
