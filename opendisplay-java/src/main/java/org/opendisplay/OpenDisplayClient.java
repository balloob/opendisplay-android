package org.opendisplay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Standalone OpenDisplay TCP client. Pure Java, no Android dependencies.
 *
 * Connects to a server at a given host:port, sends image requests,
 * handles config requests, and delivers images via a listener.
 *
 * Runs on its own background thread. Listener callbacks happen
 * on the client thread (caller must handle thread-safety).
 */
public class OpenDisplayClient {

    private static final Logger LOG = Logger.getLogger(OpenDisplayClient.class.getName());

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_FRAME_SIZE = 1024 * 1024;

    public interface Listener {
        void onConnected(String host, int port);
        void onDisconnected(String reason);
        void onImageReceived(byte[] imageData, int refreshType, long pollIntervalSeconds);
        void onNoImage(long pollIntervalSeconds);
        void onError(String message);
    }

    private final DisplayConfig config;
    private final Listener listener;
    private int batteryPercent = OpenDisplayProtocol.BATTERY_AC_POWERED;
    private int rssi = 0;

    private volatile boolean running = false;
    private Thread clientThread;
    private final Object sleepLock = new Object();

    public OpenDisplayClient(DisplayConfig config, Listener listener) {
        this.config = config;
        this.listener = listener;
    }

    public void setBatteryPercent(int percent) { this.batteryPercent = percent; }
    public void setRssi(int rssi) { this.rssi = rssi; }

    /** Starts the client in a background thread, connecting to the given server. */
    public void start(final String host, final int port) {
        if (running) return;
        running = true;

        clientThread = new Thread(new Runnable() {
            @Override
            public void run() {
                communicate(host, port);
            }
        }, "OpenDisplayClient");
        clientThread.start();
    }

    /** Stops the client and closes the connection. */
    public void stop() {
        running = false;
        synchronized (sleepLock) {
            sleepLock.notifyAll();
        }
        if (clientThread != null) {
            clientThread.interrupt();
        }
    }

    /** Blocks until the client thread has finished. */
    public void join(long timeoutMs) throws InterruptedException {
        if (clientThread != null) {
            clientThread.join(timeoutMs);
        }
    }

    public boolean isRunning() { return running; }

    private void communicate(String host, int port) {
        String disconnectReason = "Connection closed";
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            listener.onConnected(host, port);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            while (running) {
                byte[] request = OpenDisplayProtocol.buildImageRequest(batteryPercent, rssi);
                LOG.fine("Sending image request");
                out.write(request);
                out.flush();

                byte[] frame = readFrame(in);
                if (frame == null) {
                    disconnectReason = "Failed to read frame";
                    break;
                }

                OpenDisplayProtocol.ParsedFrame resp =
                    OpenDisplayProtocol.parseFrame(frame, frame.length);

                if (resp == null) {
                    disconnectReason = "Invalid frame received";
                    break;
                }

                switch (resp.packetId) {
                    case OpenDisplayProtocol.PKT_REQUEST_CONFIG:
                        LOG.info("Server requested config, sending announcement");
                        byte[] announcement = OpenDisplayProtocol.buildAnnouncement(config);
                        out.write(announcement);
                        out.flush();
                        continue;

                    case OpenDisplayProtocol.PKT_NO_IMAGE:
                        LOG.info("No new image, poll in " + resp.pollInterval + "s");
                        listener.onNoImage(resp.pollInterval);
                        sleepSeconds(resp.pollInterval);
                        break;

                    case OpenDisplayProtocol.PKT_NEW_IMAGE:
                        LOG.info("Image received: " + resp.imageLength + " bytes");
                        listener.onImageReceived(
                            resp.imageData, resp.refreshType, resp.pollInterval);
                        sleepSeconds(resp.pollInterval);
                        break;

                    default:
                        LOG.warning("Unknown packet: 0x" + Integer.toHexString(resp.packetId));
                        break;
                }
            }
        } catch (InterruptedException e) {
            disconnectReason = "Interrupted";
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Client error", e);
            disconnectReason = e.getMessage();
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // ignore
            }
            running = false;
            listener.onDisconnected(disconnectReason);
        }
    }

    private byte[] readFrame(InputStream in) throws IOException {
        byte[] lenBuf = new byte[4];
        if (!readExact(in, lenBuf, 0, 4)) return null;

        int frameLen = (lenBuf[0] & 0xFF)
                     | ((lenBuf[1] & 0xFF) << 8)
                     | ((lenBuf[2] & 0xFF) << 16)
                     | ((lenBuf[3] & 0xFF) << 24);

        if (frameLen < 7 || frameLen > MAX_FRAME_SIZE) {
            LOG.warning("Invalid frame length: " + frameLen);
            return null;
        }

        byte[] frame = new byte[frameLen];
        System.arraycopy(lenBuf, 0, frame, 0, 4);

        if (!readExact(in, frame, 4, frameLen - 4)) return null;

        return frame;
    }

    private boolean readExact(InputStream in, byte[] buf, int offset, int length)
            throws IOException {
        int remaining = length;
        while (remaining > 0) {
            int n = in.read(buf, offset, remaining);
            if (n < 0) return false;
            offset += n;
            remaining -= n;
        }
        return true;
    }

    private void sleepSeconds(long seconds) throws InterruptedException {
        if (seconds <= 0) return;
        long ms = seconds * 1000;
        synchronized (sleepLock) {
            if (running) {
                sleepLock.wait(ms);
            }
        }
    }
}
