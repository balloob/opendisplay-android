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

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final long NO_SERVER_STATUS_DELAY_MS = 5 * 60 * 1000;

    private ImageView imageDisplay;
    private TextView statusText;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private DisplayConfig displayConfig;
    private OpenDisplayClient client;
    private MdnsDiscovery discovery;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private boolean hasImage = false;
    private boolean serverConnected = false;
    private Runnable noServerStatusRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageDisplay = (ImageView) findViewById(R.id.image_display);
        statusText = (TextView) findViewById(R.id.status_text);

        hideSystemUI();

        // Tap to toggle status overlay
        imageDisplay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (statusText.getVisibility() == View.VISIBLE) {
                    statusText.setVisibility(View.GONE);
                } else {
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
        if (client != null) client.stop();
        if (discovery != null) discovery.stop();
        if (wakeLock.isHeld()) wakeLock.release();
        if (wifiLock.isHeld()) wifiLock.release();
    }

    private void startDiscovery() {
        serverConnected = false;
        Log.i(TAG, "Discovering OpenDisplay server…");

        // If no image yet, show status immediately. If we have an image,
        // only show status after 5 minutes of no server.
        if (!hasImage) {
            showStatus("Discovering OpenDisplay server…");
        } else {
            scheduleNoServerStatus();
        }

        discovery = new MdnsDiscovery(this, new MdnsDiscovery.Listener() {
            @Override
            public void onServerFound(InetAddress host, int port) {
                discovery.stop();
                connectToServer(host.getHostAddress(), port);
            }

            @Override
            public void onDiscoveryError(String message) {
                Log.e(TAG, "Discovery error: " + message);
            }
        });
        discovery.start();
    }

    private void connectToServer(final String host, final int port) {
        cancelNoServerTimer();
        Log.i(TAG, "Connecting to " + host + ":" + port);

        if (!hasImage) {
            showStatus("Connecting to " + host + ":" + port);
        }

        client = new OpenDisplayClient(displayConfig, new OpenDisplayClient.Listener() {
            @Override
            public void onConnected(String h, int p) {
                serverConnected = true;
                cancelNoServerTimer();
                Log.i(TAG, "Connected to " + h + ":" + p);
            }

            @Override
            public void onDisconnected(String reason) {
                serverConnected = false;
                Log.i(TAG, "Disconnected: " + reason);
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) startDiscovery();
                    }
                }, 5000);
            }

            @Override
            public void onImageReceived(final byte[] imageData, int refreshType, long pollInterval) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        renderImage(imageData);
                    }
                });
            }

            @Override
            public void onNoImage(long pollInterval) {
                Log.i(TAG, "No new image, polling in " + pollInterval + "s");
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Error: " + message);
            }
        });

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            if (info != null) {
                client.setRssi(info.getRssi());
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get WiFi RSSI", e);
        }

        client.start(host, port);
    }

    private void renderImage(byte[] imageData) {
        int[] pixels = ImageDecoder.decode(
            imageData, displayConfig.width, displayConfig.height, displayConfig.colorScheme);

        if (pixels != null) {
            Bitmap bmp = Bitmap.createBitmap(
                pixels, displayConfig.width, displayConfig.height, Bitmap.Config.ARGB_8888);
            imageDisplay.setImageBitmap(bmp);
            hasImage = true;
            statusText.setVisibility(View.GONE);
        } else {
            Log.w(TAG, "Failed to decode image (" + imageData.length + " bytes)");
        }
    }

    private void showStatus(String text) {
        statusText.setText(text);
        statusText.setVisibility(View.VISIBLE);
    }

    /** After 5 minutes without a server, show status over the existing image. */
    private void scheduleNoServerStatus() {
        cancelNoServerTimer();
        noServerStatusRunnable = new Runnable() {
            @Override
            public void run() {
                if (!serverConnected && hasImage) {
                    showStatus("No server found");
                }
            }
        };
        mainHandler.postDelayed(noServerStatusRunnable, NO_SERVER_STATUS_DELAY_MS);
    }

    private void cancelNoServerTimer() {
        if (noServerStatusRunnable != null) {
            mainHandler.removeCallbacks(noServerStatusRunnable);
            noServerStatusRunnable = null;
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
}
