package org.opendisplay.android;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.opendisplay.OpenDisplayProtocol;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;

/**
 * Discovers OpenDisplay servers on the local network via mDNS and maintains
 * the set of known servers.
 *
 * Handles the Android NsdManager quirk where only one resolveService() call
 * can be active at a time by queuing resolve requests and processing them
 * serially with retry on failure.
 */
public class MdnsDiscovery {

    private static final String TAG = "MdnsDiscovery";
    private static final int MAX_RESOLVE_RETRIES = 3;
    private static final long RESOLVE_RETRY_DELAY_MS = 500;

    public static class ServerInfo {
        public final String name;
        public final String host;
        public final int port;

        ServerInfo(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    public interface Listener {
        void onServerFound(ServerInfo server);
        void onServerLost(String serviceName);
        void onDiscoveryStartFailed(int errorCode);
    }

    private final NsdManager nsdManager;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private NsdManager.DiscoveryListener discoveryListener;
    private boolean discovering = false;

    private final Map<String, ServerInfo> servers = new LinkedHashMap<>();
    private final Queue<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving = false;
    private int resolveRetryCount = 0;

    public MdnsDiscovery(Context context, Listener listener) {
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    public synchronized void start() {
        if (discovering) return;
        discovering = true;

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, "Discovery started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "Found: " + serviceInfo.getServiceName());
                enqueueResolve(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                String name = serviceInfo.getServiceName();
                Log.i(TAG, "Lost: " + name);
                synchronized (MdnsDiscovery.this) {
                    servers.remove(name);
                }
                listener.onServerLost(name);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "Discovery stopped");
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Start discovery failed: " + errorCode);
                synchronized (MdnsDiscovery.this) {
                    discovering = false;
                }
                listener.onDiscoveryStartFailed(errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Stop discovery failed: " + errorCode);
            }
        };

        nsdManager.discoverServices(
            OpenDisplayProtocol.MDNS_SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener
        );
    }

    public synchronized void stop() {
        if (!discovering) return;
        discovering = false;
        resolveQueue.clear();
        resolving = false;
        servers.clear();
        try {
            nsdManager.stopServiceDiscovery(discoveryListener);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Discovery already stopped", e);
        }
    }

    /** Returns any known server, preferring one that isn't {@code exclude}. */
    public synchronized ServerInfo pickServer(String exclude) {
        ServerInfo fallback = null;
        for (ServerInfo info : servers.values()) {
            if (!info.name.equals(exclude)) return info;
            fallback = info;
        }
        return fallback;
    }

    private synchronized void enqueueResolve(NsdServiceInfo serviceInfo) {
        resolveQueue.add(serviceInfo);
        if (!resolving) {
            resolveNext();
        }
    }

    private synchronized void resolveNext() {
        NsdServiceInfo next = resolveQueue.poll();
        if (next == null) {
            resolving = false;
            return;
        }
        resolving = true;
        resolveRetryCount = 0;
        doResolve(next);
    }

    private void doResolve(final NsdServiceInfo serviceInfo) {
        nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                Log.w(TAG, "Resolve failed (" + errorCode + "): " + info.getServiceName());
                synchronized (MdnsDiscovery.this) {
                    resolveRetryCount++;
                    if (resolveRetryCount < MAX_RESOLVE_RETRIES) {
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                doResolve(serviceInfo);
                            }
                        }, RESOLVE_RETRY_DELAY_MS * resolveRetryCount);
                        return;
                    }
                    Log.e(TAG, "Giving up resolve: " + info.getServiceName());
                    resolveNext();
                }
            }

            @Override
            public void onServiceResolved(NsdServiceInfo resolved) {
                InetAddress host = resolved.getHost();
                int port = resolved.getPort();

                java.util.Map<String, byte[]> attrs = resolved.getAttributes();
                if (attrs != null && attrs.containsKey("ip")) {
                    byte[] ipBytes = attrs.get("ip");
                    if (ipBytes != null) {
                        try {
                            host = InetAddress.getByName(new String(ipBytes));
                        } catch (Exception e) {
                            Log.w(TAG, "Bad TXT ip record", e);
                        }
                    }
                }

                String name = resolved.getServiceName();
                String hostStr = host.getHostAddress();
                Log.i(TAG, "Resolved: " + name + " \u2192 " + hostStr + ":" + port);

                ServerInfo info = new ServerInfo(name, hostStr, port);
                synchronized (MdnsDiscovery.this) {
                    servers.put(name, info);
                    resolveNext();
                }
                listener.onServerFound(info);
            }
        });
    }
}
