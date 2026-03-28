package org.opendisplay;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Integration test: starts a py-opendisplay WiFi server and verifies
 * the Java client can connect, handle the config handshake, and receive an image.
 */
public class OpenDisplayIntegrationTest {

    private static final int TEST_WIDTH = 100;
    private static final int TEST_HEIGHT = 100;
    private static final int POLL_INTERVAL = 300;

    private Process serverProcess;
    private int serverPort;

    @Before
    public void startServer() throws Exception {
        File pyDir = findPyOpenDisplayDir();
        assertTrue(
            "py-opendisplay directory must exist (set PY_OPENDISPLAY_DIR or pyOpenDisplayDir to override): "
                + pyDir.getAbsolutePath(),
            pyDir.isDirectory());

        ProcessBuilder pb = new ProcessBuilder(
            "uv", "run", "opendisplay-serve",
            "--checkerboard",
            "--no-mdns",
            "--port", "0",
            "--poll-interval", String.valueOf(POLL_INTERVAL),
            "-v"
        );
        pb.directory(pyDir);
        pb.redirectErrorStream(true);
        serverProcess = pb.start();

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(serverProcess.getInputStream()));

        serverPort = 0;
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            if (reader.ready()) {
                String line = reader.readLine();
                if (line == null) break;
                System.out.println("[py-server] " + line);
                // Look for "Serving on port XXXXX" or "Server running on 0.0.0.0:XXXXX"
                if (line.contains("Serving on port")) {
                    // "Serving on port 56724 (..."
                    String after = line.substring(line.indexOf("Serving on port") + 16);
                    serverPort = Integer.parseInt(after.split("[^0-9]")[0]);
                    break;
                } else if (line.contains("Server running on")) {
                    int colonIdx = line.lastIndexOf(':');
                    if (colonIdx >= 0) {
                        String after = line.substring(colonIdx + 1).trim();
                        serverPort = Integer.parseInt(after.split("[^0-9]")[0]);
                        break;
                    }
                }
            } else {
                Thread.sleep(100);
            }
        }

        assertTrue("Server should start and print port, got: " + serverPort, serverPort > 0);
        System.out.println("Test server started on port " + serverPort);
    }

    @After
    public void stopServer() {
        if (serverProcess != null) {
            serverProcess.destroyForcibly();
            try { serverProcess.waitFor(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { /* ignore */ }
        }
    }

    @Test
    public void testConnectAndReceiveImage() throws Exception {
        DisplayConfig config = new DisplayConfig();
        config.width = TEST_WIDTH;
        config.height = TEST_HEIGHT;
        config.colorScheme = OpenDisplayProtocol.COLOR_MONOCHROME;

        final CountDownLatch imageLatch = new CountDownLatch(1);
        final CountDownLatch connectedLatch = new CountDownLatch(1);
        final AtomicReference<byte[]> receivedImage = new AtomicReference<byte[]>();
        final AtomicReference<String> errorRef = new AtomicReference<String>();

        OpenDisplayClient client = new OpenDisplayClient(config, new OpenDisplayClient.Listener() {
            @Override
            public void onConnected(String host, int port) {
                System.out.println("Connected to " + host + ":" + port);
                connectedLatch.countDown();
            }

            @Override
            public void onDisconnected(String reason) {
                System.out.println("Disconnected: " + reason);
            }

            @Override
            public void onImageReceived(byte[] imageData, int refreshType, long pollIntervalSeconds) {
                System.out.println("Received image: " + imageData.length + " bytes");
                receivedImage.set(imageData);
                imageLatch.countDown();
            }

            @Override
            public void onNoImage(long pollIntervalSeconds) {
                System.out.println("No image, poll in " + pollIntervalSeconds + "s");
            }

            @Override
            public void onError(String message) {
                System.err.println("Error: " + message);
                errorRef.set(message);
            }
        });

        client.start("127.0.0.1", serverPort);

        try {
            assertTrue("Should connect within 5s", connectedLatch.await(5, TimeUnit.SECONDS));
            assertTrue("Should receive image within 10s", imageLatch.await(10, TimeUnit.SECONDS));
            assertNull("Should have no errors", errorRef.get());

            byte[] image = receivedImage.get();
            assertNotNull("Image data should not be null", image);

            // 100x100 monochrome, row-padded: ceil(100/8) * 100 = 13 * 100 = 1300 bytes
            int bytesPerRow = (TEST_WIDTH + 7) / 8;
            int expectedSize = bytesPerRow * TEST_HEIGHT;
            assertEquals("Image size", expectedSize, image.length);

            // Decode and verify checkerboard pattern
            int[] pixels = ImageDecoder.decode(
                image, TEST_WIDTH, TEST_HEIGHT, OpenDisplayProtocol.COLOR_MONOCHROME);
            assertNotNull("Should decode", pixels);
            assertEquals(TEST_WIDTH * TEST_HEIGHT, pixels.length);

            // Cell size 8: (0,0) is black, (8,0) is white, (0,8) is white, (8,8) is black
            assertEquals("(0,0)=black", ImageDecoder.BLACK, pixels[0]);
            assertEquals("(8,0)=white", ImageDecoder.WHITE, pixels[8]);
            assertEquals("(0,8)=white", ImageDecoder.WHITE, pixels[8 * TEST_WIDTH]);
            assertEquals("(8,8)=black", ImageDecoder.BLACK, pixels[8 * TEST_WIDTH + 8]);
        } finally {
            client.stop();
            client.join(5000);
        }
    }

    private File findProjectRoot() {
        File dir = new File(System.getProperty("user.dir"));
        while (dir != null) {
            if (new File(dir, "py-opendisplay").isDirectory()) return dir;
            dir = dir.getParentFile();
        }
        return new File(System.getProperty("user.dir")).getParentFile();
    }

    private File findPyOpenDisplayDir() {
        String configuredDir = System.getenv("PY_OPENDISPLAY_DIR");
        if (configuredDir == null || configuredDir.trim().length() == 0) {
            configuredDir = System.getProperty("pyOpenDisplayDir");
        }
        if (configuredDir != null && configuredDir.trim().length() > 0) {
            return new File(configuredDir.trim());
        }

        File projectRoot = findProjectRoot();
        return new File(projectRoot, "py-opendisplay");
    }
}
