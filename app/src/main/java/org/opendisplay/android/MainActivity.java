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
    private static final long NO_SERVER_ICON_DELAY_MS = 5 * 60 * 1000;
    private static final long WHITE_FLASH_MS = 500;
    private static final long RECONNECT_DELAY_MS = 1000;
    private static final long DISCOVERY_RETRY_DELAY_MS = 5000;

    private enum State { DISCOVERING, CONNECTING, CONNECTED, DISCONNECTED }

    private ImageView imageDisplay;
    private TextView statusText;
    private ImageView disconnectIcon;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DisplayConfig displayConfig;
    private MdnsDiscovery discovery;
    private OpenDisplayClient client;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;

    private final Map<String, ServerEndpoint> servers = new LinkedHashMap<>();
    private State state = State.DISCOVERING;
    private String activeServiceName;
    private boolean hasImage = false;
    private Runnable noServerRunnable;
    private Runnable reconnectRunnable;

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

        multicastLock = wm.createMulticastLock("OpenDisplay:mdns");
        multicastLock.acquire();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        discovery = new MdnsDiscovery(this, discoveryListener);
        discovery.start();
        enterState(State.DISCOVERING);
    }

    @Override
    protected void onDestroy() {
        cancelScheduledReconnect();
        cancelNoServerTimer();
        stopClient();
        discovery.stop();
        if (wakeLock.isHeld()) wakeLock.release();
        if (wifiLock.isHeld()) wifiLock.release();
        if (multicastLock.isHeld()) multicastLock.release();
        super.onDestroy();
    }

    // -- State machine --

    private void enterState(State newState) {
        state = newState;
        Log.i(TAG, "State → " + newState);

        switch (newState) {
            case DISCOVERING:
                if (!hasImage) {
                    showStatus("Discovering OpenDisplay server\u2026");
                } else {
                    scheduleNoServerIcon();
                }
                break;

            case CONNECTING:
                cancelNoServerTimer();
                hideDisconnectIcon();
                break;

            case CONNECTED:
                cancelNoServerTimer();
                hideDisconnectIcon();
                break;

            case DISCONNECTED:
                scheduleReconnect();
                break;
        }
    }

    // -- Discovery callbacks (may arrive on NSD threads) --

    private final MdnsDiscovery.Listener discoveryListener = new MdnsDiscovery.Listener() {
        @Override
        public void onServerFound(final String name, final InetAddress host, final int port) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    handleServerFound(name, host.getHostAddress(), port);
                }
            });
        }

        @Override
        public void onServerLost(final String name) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    handleServerLost(name);
                }
            });
        }

        @Override
        public void onDiscoveryStartFailed(final int errorCode) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Log.e(TAG, "Discovery start failed: " + errorCode + ", retrying");
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) {
                                discovery.stop();
                                discovery = new MdnsDiscovery(MainActivity.this, discoveryListener);
                                discovery.start();
                            }
                        }
                    }, DISCOVERY_RETRY_DELAY_MS);
                }
            });
        }
    };

    private void handleServerFound(String name, String host, int port) {
        servers.put(name, new ServerEndpoint(host, port));
        Log.i(TAG, "Server: " + name + " at " + host + ":" + port);

        if (state == State.DISCOVERING || state == State.DISCONNECTED) {
            connectToServer(name);
        }
    }

    private void handleServerLost(String name) {
        servers.remove(name);
        Log.i(TAG, "Server gone: " + name);

        if (!name.equals(activeServiceName)) return;

        // If we have an active TCP connection, let it run until it naturally dies.
        if (state == State.CONNECTED || state == State.CONNECTING) {
            Log.i(TAG, "Active server left mDNS, keeping TCP connection");
            return;
        }

        activeServiceName = null;
        if (!connectToNextServer()) {
            enterState(State.DISCOVERING);
        }
    }

    // -- Connection --

    private boolean connectToNextServer() {
        for (Map.Entry<String, ServerEndpoint> entry : servers.entrySet()) {
            connectToServer(entry.getKey());
            return true;
        }
        return false;
    }

    private void connectToServer(String name) {
        cancelScheduledReconnect();
        stopClient();

        ServerEndpoint ep = servers.get(name);
        if (ep == null) return;

        activeServiceName = name;
        enterState(State.CONNECTING);

        if (!hasImage) {
            showStatus("Connecting to " + ep.host + ":" + ep.port);
        }

        Log.i(TAG, "Connecting to " + ep.host + ":" + ep.port);

        final OpenDisplayClient newClient = new OpenDisplayClient(displayConfig, clientListener);
        client = newClient;

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            if (info != null) newClient.setRssi(info.getRssi());
        } catch (Exception e) {
            Log.w(TAG, "Could not get WiFi RSSI", e);
        }

        newClient.start(ep.host, ep.port);
    }

    private final OpenDisplayClient.Listener clientListener = new OpenDisplayClient.Listener() {
        @Override
        public void onConnected(final String h, final int p) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "Connected to " + h + ":" + p);
                    enterState(State.CONNECTED);
                }
            });
        }

        @Override
        public void onDisconnected(final String reason) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "Disconnected: " + reason);
                    client = null;

                    // Remove the server that just failed from the pool.
                    if (activeServiceName != null) {
                        servers.remove(activeServiceName);
                        activeServiceName = null;
                    }

                    // Try another known server, else wait for discovery.
                    if (!connectToNextServer()) {
                        enterState(State.DISCONNECTED);
                    }
                }
            });
        }

        @Override
        public void onImageReceived(final byte[] data, final int refreshType, final long poll) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    renderImage(data);
                }
            });
        }

        @Override
        public void onNoImage(final long poll) {
            // Nothing to do on UI thread.
        }

        @Override
        public void onError(final String message) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Log.e(TAG, "Client error: " + message);
                }
            });
        }
    };

    private void stopClient() {
        if (client != null) {
            client.stop();
            client = null;
        }
    }

    // -- Reconnect scheduling --

    private void scheduleReconnect() {
        cancelScheduledReconnect();

        if (!hasImage) {
            showStatus("Discovering OpenDisplay server\u2026");
        } else {
            scheduleNoServerIcon();
        }

        // Discovery is still running. If a new server appears, handleServerFound
        // will connect immediately. This runnable is a fallback to retry any
        // servers that may have reappeared in the map by then.
        reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                reconnectRunnable = null;
                if (!isFinishing() && state == State.DISCONNECTED) {
                    if (!connectToNextServer()) {
                        // Still nothing — keep waiting, discovery will trigger us.
                        scheduleReconnect();
                    }
                }
            }
        };
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
    }

    private void cancelScheduledReconnect() {
        if (reconnectRunnable != null) {
            handler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    // -- UI helpers --

    private void renderImage(byte[] imageData) {
        int[] pixels = ImageDecoder.decode(
            imageData, displayConfig.width, displayConfig.height, displayConfig.colorScheme);

        if (pixels == null) {
            Log.w(TAG, "Failed to decode image (" + imageData.length + " bytes)");
            return;
        }

        final Bitmap bmp = Bitmap.createBitmap(
            pixels, displayConfig.width, displayConfig.height, Bitmap.Config.ARGB_8888);

        if (hasImage) {
            imageDisplay.setImageBitmap(null);
            handler.postDelayed(new Runnable() {
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
    }

    private void showStatus(String text) {
        statusText.setText(text);
        statusText.setVisibility(View.VISIBLE);
    }

    private void hideDisconnectIcon() {
        disconnectIcon.setVisibility(View.GONE);
    }

    private void scheduleNoServerIcon() {
        cancelNoServerTimer();
        noServerRunnable = new Runnable() {
            @Override
            public void run() {
                if (state != State.CONNECTED && hasImage) {
                    disconnectIcon.setVisibility(View.VISIBLE);
                }
            }
        };
        handler.postDelayed(noServerRunnable, NO_SERVER_ICON_DELAY_MS);
    }

    private void cancelNoServerTimer() {
        if (noServerRunnable != null) {
            handler.removeCallbacks(noServerRunnable);
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
        Log.i(TAG, "Display: " + cfg.width + "x" + cfg.height);
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
    }
}
