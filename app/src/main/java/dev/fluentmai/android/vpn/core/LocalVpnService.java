package dev.fluentmai.android.vpn.core;

import static dev.fluentmai.android.vpn.core.Constant.TAG;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.fluentmai.android.MainActivity;
import dev.fluentmai.android.R;
import dev.fluentmai.android.WahlapHookBridge;
import dev.fluentmai.android.vpn.dns.DnsPacket;
import dev.fluentmai.android.vpn.tcpip.CommonMethods;
import dev.fluentmai.android.vpn.tcpip.IPHeader;
import dev.fluentmai.android.vpn.tcpip.TCPHeader;
import dev.fluentmai.android.vpn.tcpip.UDPHeader;


public class LocalVpnService extends VpnService implements Runnable {
    public static final String DISCONNECT_INTENT = "io.github.skkydynamic.service.vpn.DISCONNECT";
    private static final String NOTIFICATION_CHANNEL_ID = "fluentmai_hook";
    private static final int NOTIFICATION_ID = 8285;
    private static final String WAHLAP_CAPTURE_HOST = "tgk-wcaime.wahlap.com";

    public static LocalVpnService Instance;
    public static volatile boolean IsRunning = false;
    private static int ID;
    private static int LOCAL_IP;
    private static final ConcurrentHashMap<onStatusChangedListener, Object> m_OnStatusChangedListeners = new ConcurrentHashMap<onStatusChangedListener, Object>();

    private volatile Thread m_VPNThread;
    private ParcelFileDescriptor m_VPNInterface;
    private TcpProxyServer m_TcpProxyServer;
    private DnsProxy m_DnsProxy;
    private FileOutputStream m_VPNOutputStream;

    private final byte[] m_Packet;
    private final IPHeader m_IPHeader;
    private final TCPHeader m_TCPHeader;
    private final UDPHeader m_UDPHeader;
    private final ByteBuffer m_DNSBuffer;
    private final Handler m_Handler;
    private long m_SentBytes;
    private long m_ReceivedBytes;
    private String[] m_Blacklist;

    public LocalVpnService() {
        ID++;
        m_Handler = Handler.createAsync(Looper.getMainLooper());
        m_Packet = new byte[20000];
        m_IPHeader = new IPHeader(m_Packet, 0);
        m_TCPHeader = new TCPHeader(m_Packet, 20);
        m_UDPHeader = new UDPHeader(m_Packet, 20);
        m_DNSBuffer = ((ByteBuffer) ByteBuffer.wrap(m_Packet).position(28)).slice();
        Instance = this;

        Log.d("VpnProxy", "New VPNService" + ID);
    }

    public static void addOnStatusChangedListener(onStatusChangedListener listener) {
        if (!m_OnStatusChangedListeners.containsKey(listener)) {
            m_OnStatusChangedListeners.put(listener, 1);
        }
    }

    public static void removeOnStatusChangedListener(onStatusChangedListener listener) {
        m_OnStatusChangedListeners.remove(listener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || Objects.equals(intent.getAction(), DISCONNECT_INTENT)) {
            shutdown();
            return START_NOT_STICKY;
        }

        promoteToForeground();
        if (m_VPNThread != null) return START_NOT_STICKY;
        IsRunning = true;
        WahlapHookBridge.setVpnRunning(true);
        try {
            m_TcpProxyServer = new TcpProxyServer(0);
            m_TcpProxyServer.start();
            writeLog("LocalTcpServer started.");

            m_DnsProxy = new DnsProxy();
            m_DnsProxy.start();
        } catch (IOException e) {
            shutdown();
            return START_NOT_STICKY;
        }
        m_VPNThread = new Thread(this, "VPNServiceThread");
        m_VPNThread.start();
        return START_NOT_STICKY;
    }

    private void promoteToForeground() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "FluentMai Hook",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持微信 Hook 捕获 VPN 运行");
            notificationManager.createNotificationChannel(channel);
        }

        Intent openAppIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("FluentMai 正在捕获微信授权")
                .setContentText("保持 VPN 运行以读取舞萌授权跳转")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (VpnService.SERVICE_INTERFACE.equals(action)) {
            return super.onBind(intent);
        }
        return null;
    }

    private void onStatusChanged(final String status, final boolean isRunning) {
        m_Handler.post(() -> {
            for (Map.Entry<onStatusChangedListener, Object> entry : m_OnStatusChangedListeners.entrySet()) {
                entry.getKey().onStatusChanged(status, isRunning);
            }
        });
    }

    public void writeLog(final String format, Object... args) {
        final String logString = String.format(format, args);
        m_Handler.post(() -> {
            for (Map.Entry<onStatusChangedListener, Object> entry : m_OnStatusChangedListeners.entrySet()) {
                entry.getKey().onLogReceived(logString);
            }
        });
    }

    public void sendUDPPacket(IPHeader ipHeader, UDPHeader udpHeader) {
        try {
            CommonMethods.ComputeUDPChecksum(ipHeader, udpHeader);
            this.m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, ipHeader.getTotalLength());
        } catch (IOException ignored) {
        }
    }

    String getAppInstallID() {
        SharedPreferences preferences = getSharedPreferences(TAG, MODE_PRIVATE);
        String appInstallID = preferences.getString("AppInstallID", null);
        if (appInstallID == null || appInstallID.isEmpty()) {
            appInstallID = UUID.randomUUID().toString();
            Editor editor = preferences.edit();
            editor.putString("AppInstallID", appInstallID);
            editor.apply();
        }
        return appInstallID;
    }

    String getVersionName() {
        try {
            PackageManager packageManager = getPackageManager();
            PackageInfo packInfo = packageManager.getPackageInfo(getPackageName(), 0);
            return packInfo.versionName;
        } catch (Exception e) {
            return "0.0";
        }
    }

    @Override
    public void run() {
        try {
            Log.d(TAG, "VPNService work thread is running... " + ID);

            ProxyConfig.AppInstallID = getAppInstallID();
            ProxyConfig.AppVersion = getVersionName();
            writeLog("Android version: %s", Build.VERSION.RELEASE);
            writeLog("App version: %s", ProxyConfig.AppVersion);

            waitUntilPreapred();

            runVPN();

        } catch (InterruptedException e) {
            Log.e(TAG, "Exception", e);
        } catch (Exception e) {
            writeLog("Fatal error: %s", e.toString());
        }

        writeLog("VpnProxy terminated.");
        Thread worker = Thread.currentThread();
        m_Handler.post(() -> {
            // A previous worker must never tear down a newly started capture.
            if (m_VPNThread == worker) shutdown();
        });
    }

    private void runVPN() throws Exception {
        ParcelFileDescriptor descriptor = establishVPN();
        if (descriptor == null) return;
        try (FileInputStream in = new FileInputStream(descriptor.getFileDescriptor())) {
            while (IsRunning && m_VPNThread == Thread.currentThread()) {
                boolean idle = true;
                int size = in.read(m_Packet);
                if (size > 0) {
                    if (m_DnsProxy.Stopped || m_TcpProxyServer.Stopped) {
                        in.close();
                        throw new Exception("LocalServer stopped.");
                    }
                    try {
                        onIPPacketReceived(m_IPHeader, size);
                        idle = false;
                    } catch (IOException ex) {
                        Log.e(TAG, "IOException when processing IP packet", ex);
                    }
                }
                if (idle) {
                    Thread.sleep(100);
                }
            }
        }
    }

    void onIPPacketReceived(IPHeader ipHeader, int size) throws IOException {
        switch (ipHeader.getProtocol()) {
            case IPHeader.TCP:
                TCPHeader tcpHeader = m_TCPHeader;
                tcpHeader.m_Offset = ipHeader.getHeaderLength();
                if (ipHeader.getSourceIP() == LOCAL_IP) {
                    if (tcpHeader.getSourcePort() == m_TcpProxyServer.Port) {
                        NatSession session = NatSessionManager.getSession(tcpHeader.getDestinationPort());
                        if (session != null) {
                            ipHeader.setSourceIP(ipHeader.getDestinationIP());
                            tcpHeader.setSourcePort(session.RemotePort);
                            ipHeader.setDestinationIP(LOCAL_IP);

                            CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                            m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                            m_ReceivedBytes += size;
                        }
                    } else {
                        int portKey = tcpHeader.getSourcePort();
                        NatSession session = NatSessionManager.getSession(portKey);
                        if (session == null || session.RemoteIP != ipHeader.getDestinationIP() || session.RemotePort != tcpHeader.getDestinationPort()) {
                            session = NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), tcpHeader.getDestinationPort());
                        }

                        session.LastNanoTime = System.nanoTime();
                        session.PacketSent++;

                        int tcpDataSize = ipHeader.getDataLength() - tcpHeader.getHeaderLength();
                        if (session.PacketSent == 2 && tcpDataSize == 0) {
                            return;
                        }

                        if (session.BytesSent == 0 && tcpDataSize > 10) {
                            int dataOffset = tcpHeader.m_Offset + tcpHeader.getHeaderLength();
                            String host = HttpHostHeaderParser.parseHost(tcpHeader.m_Data, dataOffset, tcpDataSize);
                            if (host != null) {
                                session.RemoteHost = host;
                            }
                        }

                        ipHeader.setSourceIP(ipHeader.getDestinationIP());
                        ipHeader.setDestinationIP(LOCAL_IP);
                        tcpHeader.setDestinationPort(m_TcpProxyServer.Port);
                        if (session.PacketSent <= 3 &&
                                ((session.RemotePort & 0xFFFF) == 80 || DnsProxy.reverseLookup(session.RemoteIP) != null)) {
                            Log.i(TAG, "Redirect TCP " + session.RemoteHost + ":" + (session.RemotePort & 0xFFFF) +
                                    " via local port " + (m_TcpProxyServer.Port & 0xFFFF) +
                                    " data=" + tcpDataSize);
                        }

                        CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                        m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                        session.BytesSent += tcpDataSize;
                        m_SentBytes += size;
                    }
                }
                break;
            case IPHeader.UDP:
                UDPHeader udpHeader = m_UDPHeader;
                udpHeader.m_Offset = ipHeader.getHeaderLength();
                if (ipHeader.getSourceIP() == LOCAL_IP && udpHeader.getDestinationPort() == 53) {
                    m_DNSBuffer.clear();
                    m_DNSBuffer.limit(ipHeader.getDataLength() - 8);
                    DnsPacket dnsPacket = DnsPacket.FromBytes(m_DNSBuffer);
                    if (dnsPacket != null && dnsPacket.Header.QuestionCount > 0) {
                        m_DnsProxy.onDnsRequestReceived(ipHeader, udpHeader, dnsPacket);
                    }
                }
                break;
        }
    }

    private void waitUntilPreapred() throws InterruptedException {
        while (IsRunning && m_VPNThread == Thread.currentThread() && prepare(this) != null) {
            Thread.sleep(100);
        }
    }

    private ParcelFileDescriptor establishVPN() throws IOException {

        NatSessionManager.clearAllSessions();

        Builder builder = new Builder();
        builder.setMtu(ProxyConfig.Instance.getMTU());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.allowBypass();
        }

        ProxyConfig.IPAddress ipAddress = ProxyConfig.Instance.getDefaultLocalIP();
        LOCAL_IP = CommonMethods.ipStringToInt(ipAddress.Address);

        builder.addAddress(ipAddress.Address, ipAddress.PrefixLength);

        if (m_Blacklist == null) {
            m_Blacklist = getResources().getStringArray(R.array.black_list);
        }

        ProxyConfig.Instance.resetDomain(m_Blacklist);

        for (ProxyConfig.IPAddress dnsAddress : ProxyConfig.Instance.getDnsList()) {
            builder.addDnsServer(dnsAddress.Address);
            builder.addRoute(dnsAddress.Address, 32);
        }

        addCaptureHostRoutes(builder);

        builder.addRoute(CommonMethods.ipIntToString(ProxyConfig.FAKE_NETWORK_IP), 16);

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        builder.setConfigureIntent(pendingIntent);

        builder.setSession(ProxyConfig.Instance.getSessionName());

        synchronized (this) {
            // DNS above may finish after cancellation. Never recreate a stopped tunnel.
            if (!IsRunning || m_VPNThread != Thread.currentThread()) return null;
            m_VPNInterface = builder.establish();
            if (m_VPNInterface == null) return null;
            m_VPNOutputStream = new FileOutputStream(m_VPNInterface.getFileDescriptor());
            onStatusChanged(ProxyConfig.Instance.getSessionName() + " " + getString(R.string.vpn_connected_status), true);
            return m_VPNInterface;
        }
    }

    private void addCaptureHostRoutes(Builder builder) {
        try {
            for (InetAddress address : InetAddress.getAllByName(WAHLAP_CAPTURE_HOST)) {
                if (address instanceof Inet4Address) {
                    String hostAddress = address.getHostAddress();
                    int ip = CommonMethods.ipStringToInt(hostAddress);
                    DnsProxy.addStaticMapping(WAHLAP_CAPTURE_HOST, ip);
                    builder.addRoute(hostAddress, 32);
                    Log.i(TAG, "Capture route " + WAHLAP_CAPTURE_HOST + " -> " + hostAddress);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to resolve capture host route for " + WAHLAP_CAPTURE_HOST, e);
        }
    }

    private synchronized void dispose() {
        onStatusChanged(ProxyConfig.Instance.getSessionName() + " " + getString(R.string.vpn_disconnected_status), false);

        IsRunning = false;
        WahlapHookBridge.setVpnRunning(false);

        try {
            if (m_VPNInterface != null) {
                m_VPNInterface.close();
                m_VPNInterface = null;
            }
        } catch (Exception e) {
            // ignore
        }

        try {
            if (m_VPNOutputStream != null) {
                m_VPNOutputStream.close();
                m_VPNOutputStream = null;
            }
        } catch (Exception ignored) {
        }

        if (m_VPNThread != null) {
            m_VPNThread.interrupt();
            m_VPNThread = null;
        }
    }

    private void shutdown() {
        dispose();
        try {
            if (m_TcpProxyServer != null) {
                m_TcpProxyServer.stop();
                m_TcpProxyServer = null;
            }
        } catch (Exception ignored) {
        }

        try {
            if (m_DnsProxy != null) {
                m_DnsProxy.stop();
                m_DnsProxy = null;
            }
        } catch (Exception ignored) {
        }
        stopForeground(true);
        getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        stopSelf();
    }

    @Override
    public void onRevoke() {
        shutdown();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        shutdown();
        if (Instance == this) Instance = null;
        super.onDestroy();
    }

    public interface onStatusChangedListener {
        void onStatusChanged(String status, Boolean isRunning);
        void onLogReceived(String logString);
    }
}

