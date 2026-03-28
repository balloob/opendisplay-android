package org.opendisplay.android;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import org.opendisplay.OpenDisplayProtocol;

import java.net.InetAddress;

/**
 * Discovers OpenDisplay servers on the local network via mDNS.
 * Looks for _opendisplay._tcp. services.
 */
public class MdnsDiscovery {

    private static final String TAG = "MdnsDiscovery";

    public interface Listener {
        void onServerFound(String serviceName, InetAddress host, int port);
        void onServerLost(String serviceName);
        void onDiscoveryError(String message);
    }

    private final NsdManager nsdManager;
    private final Listener listener;
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean discovering = false;

    public MdnsDiscovery(Context context, Listener listener) {
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    public synchronized void start() {
        if (discovering) return;

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, "Discovery started for " + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "Service found: " + serviceInfo.getServiceName());
                resolve(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "Service lost: " + serviceInfo.getServiceName());
                listener.onServerLost(serviceInfo.getServiceName());
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "Discovery stopped");
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Start discovery failed: " + errorCode);
                discovering = false;
                listener.onDiscoveryError("mDNS discovery failed (error " + errorCode + ")");
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
        discovering = true;
    }

    public synchronized void stop() {
        if (!discovering) return;
        try {
            nsdManager.stopServiceDiscovery(discoveryListener);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Discovery already stopped", e);
        }
        discovering = false;
    }

    private void resolve(NsdServiceInfo serviceInfo) {
        nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "Resolve failed: " + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                int port = serviceInfo.getPort();
                InetAddress host = serviceInfo.getHost();

                // Per spec: prefer TXT record "ip" key for address
                java.util.Map<String, byte[]> attrs = serviceInfo.getAttributes();
                if (attrs != null && attrs.containsKey("ip")) {
                    byte[] ipBytes = attrs.get("ip");
                    if (ipBytes != null) {
                        String ipStr = new String(ipBytes);
                        try {
                            host = InetAddress.getByName(ipStr);
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to parse TXT ip record: " + ipStr, e);
                        }
                    }
                }

                Log.i(TAG, "Resolved: " + host + ":" + port);
                listener.onServerFound(serviceInfo.getServiceName(), host, port);
            }
        });
    }
}
