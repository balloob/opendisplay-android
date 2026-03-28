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

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final long NO_SERVER_ICON_DELAY_MS = 5 * 60 * 1000;
    private static final long WHITE_FLASH_MS = 500;
    private static final long RECONNECT_DELAY_MS = 2000;
    private static final long DISCOVERY_RETRY_DELAY_MS = 5000;

    private ImageView imageDisplay;
    private TextView statusText;
    private ImageView disconnectIcon;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DisplayConfig displayConfig;
    private MdnsDiscovery discovery;
    private OpenDisplayClient client;
    private int connectionGen;
    private String activeServerName;
    private boolean hasImage = false;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;
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
        acquireLocks();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        discovery = new MdnsDiscovery(this, discoveryListener);
        discovery.start();
        showWaiting();
    }

    @Override
    protected void onDestroy() {
        cancelScheduledReconnect();
        cancelNoServerTimer();
        stopClient();
        discovery.stop();
        releaseLocks();
        super.onDestroy();
    }

    // -- Discovery --

    private final MdnsDiscovery.Listener discoveryListener = new MdnsDiscovery.Listener() {
        @Override
        public void onServerFound(final MdnsDiscovery.ServerInfo server) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "Server: " + server.name + " at " + server.host + ":" + server.port);
                    if (client == null) {
                        connectTo(server);
                    }
                }
            });
        }

        @Override
        public void onServerLost(final String name) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "Server gone: " + name);
                    if (!name.equals(activeServerName)) return;
                    if (client != null) {
                        Log.i(TAG, "Active server left mDNS, keeping TCP connection");
                        return;
                    }
                    tryConnect();
                }
            });
        }

        @Override
        public void onDiscoveryStartFailed(final int errorCode) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Log.e(TAG, "Discovery failed: " + errorCode + ", retrying");
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) discovery.start();
                        }
                    }, DISCOVERY_RETRY_DELAY_MS);
                }
            });
        }
    };

    // -- Connection --

    private void tryConnect() {
        MdnsDiscovery.ServerInfo server = discovery.pickServer(activeServerName);
        activeServerName = null;
        if (server != null) {
            connectTo(server);
        } else {
            showWaiting();
        }
    }

    private void connectTo(MdnsDiscovery.ServerInfo server) {
        cancelScheduledReconnect();
        stopClient();

        activeServerName = server.name;
        final int gen = ++connectionGen;

        if (!hasImage) {
            showStatus("Connecting to " + server.host + ":" + server.port);
        }
        cancelNoServerTimer();
        hideDisconnectIcon();

        Log.i(TAG, "Connecting to " + server.host + ":" + server.port);

        final OpenDisplayClient newClient = new OpenDisplayClient(
            displayConfig, new OpenDisplayClient.Listener() {
                @Override
                public void onConnected(final String h, final int p) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != connectionGen) return;
                            Log.i(TAG, "Connected to " + h + ":" + p);
                            cancelNoServerTimer();
                            hideDisconnectIcon();
                        }
                    });
                }

                @Override
                public void onDisconnected(final String reason) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != connectionGen) return;
                            Log.i(TAG, "Disconnected: " + reason);
                            client = null;

                            // Try a different server immediately, or retry after a delay.
                            MdnsDiscovery.ServerInfo next =
                                discovery.pickServer(activeServerName);
                            activeServerName = null;

                            if (next != null) {
                                connectTo(next);
                            } else {
                                showWaiting();
                                scheduleReconnect();
                            }
                        }
                    });
                }

                @Override
                public void onImageReceived(final byte[] data, final int refreshType,
                        final long poll) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != connectionGen) return;
                            renderImage(data);
                        }
                    });
                }

                @Override
                public void onNoImage(long poll) {}

                @Override
                public void onError(final String message) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != connectionGen) return;
                            Log.e(TAG, "Client error: " + message);
                        }
                    });
                }
            });
        client = newClient;

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            if (info != null) newClient.setRssi(info.getRssi());
        } catch (Exception e) {
            Log.w(TAG, "Could not get WiFi RSSI", e);
        }

        newClient.start(server.host, server.port);
    }

    private void stopClient() {
        if (client != null) {
            client.stop();
            client = null;
        }
    }

    /**
     * After a disconnect with no alternative server, retry once after a delay.
     * Discovery is still running — if a new server appears, onServerFound
     * connects immediately and this is cancelled.
     */
    private void scheduleReconnect() {
        cancelScheduledReconnect();
        reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                reconnectRunnable = null;
                if (!isFinishing() && client == null) {
                    MdnsDiscovery.ServerInfo server = discovery.pickServer(null);
                    if (server != null) connectTo(server);
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

    // -- UI --

    private void showWaiting() {
        if (!hasImage) {
            showStatus("Discovering OpenDisplay server\u2026");
        } else {
            scheduleNoServerIcon();
        }
    }

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
                if (client == null && hasImage) {
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

    // -- Setup --

    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenDisplay:poll");
        wakeLock.acquire();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "OpenDisplay:wifi");
        wifiLock.acquire();

        multicastLock = wm.createMulticastLock("OpenDisplay:mdns");
        multicastLock.acquire();
    }

    private void releaseLocks() {
        if (wakeLock.isHeld()) wakeLock.release();
        if (wifiLock.isHeld()) wifiLock.release();
        if (multicastLock.isHeld()) multicastLock.release();
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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }
}
