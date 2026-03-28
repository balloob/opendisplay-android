package org.opendisplay.android;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import org.opendisplay.DisplayConfig;
import org.opendisplay.ImageDecoder;
import org.opendisplay.OpenDisplayClient;
import org.opendisplay.OpenDisplayProtocol;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final long DISCOVERY_RESTART_DELAY_MS = 1000;
    private static final long NO_SERVER_ICON_DELAY_MS = 5 * 60 * 1000;
    private static final long WHITE_FLASH_MS = 500;

    private ImageView imageDisplay;
    private TextView statusText;
    private ImageView disconnectIcon;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private DisplayConfig displayConfig;
    private OpenDisplayClient client;
    private MdnsDiscovery discovery;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final Map<String, ServerEndpoint> discoveredServers =
        new LinkedHashMap<String, ServerEndpoint>();

    private boolean hasImage = false;
    private boolean serverConnected = false;
    private boolean connectionPending = false;
    private Runnable noServerRunnable;
    private Runnable discoveryRestartRunnable;
    private String activeServiceName;
    private String activeHost;
    private int activePort = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageDisplay = (ImageView) findViewById(R.id.image_display);
        statusText = (TextView) findViewById(R.id.status_text);
        disconnectIcon = (ImageView) findViewById(R.id.disconnect_icon);

        hideSystemUI();

        imageDisplay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (statusText.getVisibility() == View.VISIBLE) {
                    statusText.setVisibility(View.GONE);
                } else if (hasImage) {
                    statusText.setVisibility(View.VISIBLE);
                }
            }
        });

        displayConfig = buildDisplayConfig();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenDisplay:poll");
        wakeLock.acquire();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "OpenDisplay:wifi");
        wifiLock.acquire();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        startDiscovery();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelNoServerTimer();
        cancelDiscoveryRestart();
        stopClient();
        if (discovery != null) discovery.stop();
        if (wakeLock.isHeld()) wakeLock.release();
        if (wifiLock.isHeld()) wifiLock.release();
    }

    private void startDiscovery() {
        cancelDiscoveryRestart();
        Log.i(TAG, "Discovering OpenDisplay server…");
        showDiscoveryState();

        if (discovery == null) {
            discovery = new MdnsDiscovery(this, new MdnsDiscovery.Listener() {
                @Override
                public void onServerFound(
                        final String serviceName, final InetAddress host, final int port) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            handleServerFound(serviceName, host, port);
                        }
                    });
                }

                @Override
                public void onServerLost(final String serviceName) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            handleServerLost(serviceName);
                        }
                    });
                }

                @Override
                public void onDiscoveryError(final String message) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Log.e(TAG, "Discovery error: " + message);
                        }
                    });
                }
            });
        }
        discovery.start();
    }

    private void handleServerFound(String serviceName, InetAddress host, int port) {
        String hostAddress = host.getHostAddress();
        discoveredServers.put(serviceName, new ServerEndpoint(hostAddress, port));
        Log.i(TAG, "Server available: " + serviceName + " at " + hostAddress + ":" + port);

        if (isActiveServer(serviceName, hostAddress, port)) {
            return;
        }

        if (activeServiceName == null && client == null && !connectionPending) {
            connectToAvailableServer();
        }
    }

    private void handleServerLost(String serviceName) {
        discoveredServers.remove(serviceName);
        Log.i(TAG, "Server disappeared: " + serviceName);

        if (!serviceName.equals(activeServiceName)) {
            return;
        }

        if (client != null || connectionPending) {
            Log.i(
                TAG,
                "Active server disappeared from mDNS, keeping current TCP connection until it closes"
            );
            return;
        }

        Log.i(TAG, "Current server disappeared, looking for another OpenDisplay server");
        clearActiveServer();

        if (!connectToAvailableServer()) {
            showDiscoveryState();
        }
    }

    private boolean connectToAvailableServer() {
        if (activeServiceName != null || client != null || connectionPending) {
            return false;
        }

        for (Map.Entry<String, ServerEndpoint> entry : discoveredServers.entrySet()) {
            ServerEndpoint endpoint = entry.getValue();
            connectToServer(entry.getKey(), endpoint.host, endpoint.port);
            return true;
        }

        return false;
    }

    private void connectToServer(final String serviceName, final String host, final int port) {
        cancelNoServerTimer();
        cancelDiscoveryRestart();
        hideDisconnectIcon();
        Log.i(TAG, "Connecting to " + host + ":" + port);

        stopClient();
        activeServiceName = serviceName;
        activeHost = host;
        activePort = port;
        connectionPending = true;
        serverConnected = false;

        if (!hasImage) {
            showStatus("Connecting to " + host + ":" + port);
        }

        final ClientRef clientRef = new ClientRef();
        final OpenDisplayClient newClient = new OpenDisplayClient(
            displayConfig, new OpenDisplayClient.Listener() {
            @Override
            public void onConnected(final String h, final int p) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (client != clientRef.client) return;
                        connectionPending = false;
                        serverConnected = true;
                        cancelNoServerTimer();
                        hideDisconnectIcon();
                        Log.i(TAG, "Connected to " + h + ":" + p);
                    }
                });
            }

            @Override
            public void onDisconnected(final String reason) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (client != clientRef.client) return;
                        client = null;
                        connectionPending = false;
                        serverConnected = false;
                        Log.i(TAG, "Disconnected: " + reason);

                        if (activeServiceName != null) {
                            discoveredServers.remove(activeServiceName);
                        }
                        clearActiveServer();

                        if (!connectToAvailableServer()) {
                            scheduleDiscoveryRestart();
                        }
                    }
                });
            }

            @Override
            public void onImageReceived(final byte[] imageData, final int refreshType, final long pollInterval) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (client != clientRef.client) return;
                        renderImage(imageData);
                    }
                });
            }

            @Override
            public void onNoImage(final long pollInterval) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (client != clientRef.client) return;
                        Log.i(TAG, "No new image, polling in " + pollInterval + "s");
                    }
                });
            }

            @Override
            public void onError(final String message) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (client != clientRef.client) return;
                        Log.e(TAG, "Error: " + message);
                    }
                });
            }
        });
        clientRef.client = newClient;
        client = newClient;

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            if (info != null) {
                newClient.setRssi(info.getRssi());
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get WiFi RSSI", e);
        }

        newClient.start(host, port);
    }

    private void renderImage(byte[] imageData) {
        int[] pixels = ImageDecoder.decode(
            imageData, displayConfig.width, displayConfig.height, displayConfig.colorScheme);

        if (pixels != null) {
            final Bitmap bmp = Bitmap.createBitmap(
                pixels, displayConfig.width, displayConfig.height, Bitmap.Config.ARGB_8888);

            if (hasImage) {
                // Flash white first to clear e-ink ghosting
                imageDisplay.setImageBitmap(null);
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        imageDisplay.setImageBitmap(bmp);
                    }
                }, WHITE_FLASH_MS);
            } else {
                imageDisplay.setImageBitmap(bmp);
            }

            hasImage = true;
            statusText.setVisibility(View.GONE);
            hideDisconnectIcon();
        } else {
            Log.w(TAG, "Failed to decode image (" + imageData.length + " bytes)");
        }
    }

    private void showStatus(String text) {
        statusText.setText(text);
        statusText.setVisibility(View.VISIBLE);
    }

    private void showDisconnectIcon() {
        disconnectIcon.setVisibility(View.VISIBLE);
    }

    private void hideDisconnectIcon() {
        disconnectIcon.setVisibility(View.GONE);
    }

    private void showDiscoveryState() {
        serverConnected = false;
        connectionPending = false;

        if (!hasImage) {
            showStatus("Discovering OpenDisplay server…");
        } else {
            scheduleNoServerIcon();
        }
    }

    private void stopClient() {
        if (client == null) {
            return;
        }

        OpenDisplayClient activeClient = client;
        client = null;
        activeClient.stop();
    }

    private void clearActiveServer() {
        activeServiceName = null;
        activeHost = null;
        activePort = -1;
    }

    private void scheduleDiscoveryRestart() {
        showDiscoveryState();

        if (isFinishing()) {
            return;
        }

        cancelDiscoveryRestart();
        discoveredServers.clear();

        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }

        discoveryRestartRunnable = new Runnable() {
            @Override
            public void run() {
                discoveryRestartRunnable = null;
                if (!isFinishing()) {
                    startDiscovery();
                }
            }
        };
        mainHandler.postDelayed(discoveryRestartRunnable, DISCOVERY_RESTART_DELAY_MS);
    }

    private void cancelDiscoveryRestart() {
        if (discoveryRestartRunnable != null) {
            mainHandler.removeCallbacks(discoveryRestartRunnable);
            discoveryRestartRunnable = null;
        }
    }

    private boolean isActiveServer(String serviceName, String host, int port) {
        if (!serviceName.equals(activeServiceName) || activeHost == null) {
            return false;
        }

        return activeHost.equals(host) && activePort == port;
    }

    /** After 5 minutes without a server, show disconnect icon. */
    private void scheduleNoServerIcon() {
        cancelNoServerTimer();
        noServerRunnable = new Runnable() {
            @Override
            public void run() {
                if (!serverConnected && hasImage) {
                    showDisconnectIcon();
                }
            }
        };
        mainHandler.postDelayed(noServerRunnable, NO_SERVER_ICON_DELAY_MS);
    }

    private void cancelNoServerTimer() {
        if (noServerRunnable != null) {
            mainHandler.removeCallbacks(noServerRunnable);
            noServerRunnable = null;
        }
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    private DisplayConfig buildDisplayConfig() {
        DisplayConfig cfg = new DisplayConfig();

        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);

        cfg.width = Math.max(size.x, size.y);
        cfg.height = Math.min(size.x, size.y);
        cfg.colorScheme = OpenDisplayProtocol.COLOR_MONOCHROME;

        Log.i(TAG, "Display config: " + cfg.width + "x" + cfg.height
            + " scheme=" + cfg.colorScheme);

        return cfg;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    private static class ServerEndpoint {
        final String host;
        final int port;

        ServerEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        boolean matches(String otherHost, int otherPort) {
            return host.equals(otherHost) && port == otherPort;
        }
    }

    private static class ClientRef {
        OpenDisplayClient client;
    }
}
