package com.roottun.probe;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.PortUnreachableException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 「目标应用视角」探针：只使用普通应用权限，逐项检查代理是否会暴露。
 *
 * 1. NetworkCapabilities 是否报告 TRANSPORT_VPN
 * 2. NetworkInterface.getNetworkInterfaces()（即 getifaddrs）里是否出现 tun/wg/ppp 等接口
 * 3. /proc/net/route 与 /proc/net/ipv6_route 是否出现可疑接口
 * 4. 能否连接 127.0.0.1 上的 mihomo 端口（mixed-port / controller / DNS）
 * 5. /proc/net/if_inet6 是否出现可疑接口
 *
 * 结果同时写入 logcat（tag=ROOTTUN_PROBE）与 files/probe-report.txt，便于 adb 抓取。
 */
public class ProbeActivity extends Activity {

    private static final String TAG = "ROOTTUN_PROBE";
    private static final String REPORT_FILE = "probe-report.txt";
    private static final int[] PORTS = {7890, 9090, 1053};
    private static final Pattern VPN_LIKE = Pattern.compile(
            "(?i)(tun|tap|ppp|wg|ipsec|xfrm|utun|l2tp|gre|vpn)");

    private final StringBuilder out = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView textView = new TextView(this);
        textView.setTextSize(11f);
        textView.setPadding(24, 24, 24, 24);
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(textView);
        setContentView(scrollView);

        new Thread(() -> {
            String report;
            try {
                report = runChecks();
            } catch (Throwable t) {
                report = "PROBE CRASHED: " + t;
            }
            try {
                File file = new File(getFilesDir(), REPORT_FILE);
                FileWriter writer = new FileWriter(file, false);
                writer.write(report);
                writer.close();
            } catch (Throwable t) {
                Log.e(TAG, "write report failed: " + t);
            }
            for (String line : report.split("\n")) {
                Log.i(TAG, line);
            }
            final String finalReport = report;
            runOnUiThread(() -> textView.setText(finalReport));
        }, "probe").start();
    }

    private String runChecks() {
        section("RootTun Probe");
        line("time=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        line("package=" + getPackageName() + " uid=" + android.os.Process.myUid());
        line("device=" + Build.MANUFACTURER + " " + Build.MODEL + " / Android "
                + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        line("abi=" + String.join(",", Build.SUPPORTED_ABIS));

        checkVpnTransport();
        checkNetworkInterfaces();
        checkRouteFiles();
        checkLocalPorts();
        checkIfInet6();

        section("SUMMARY");
        line("RESULT_1_VPN_TRANSPORT_DETECTED=" + result(1));
        line("RESULT_2_VPN_INTERFACE_DETECTED=" + result(2));
        line("RESULT_3_VPN_ROUTE_VISIBLE=" + (routeReadable ? result(3) : "UNKNOWN (UNREADABLE)"));
        line("RESULT_4_PROXY_PORT_REACHABLE=" + result(4));
        line("RESULT_5_IF_INET6_LEAK=" + (ifInet6Readable ? result(5) : "UNKNOWN (UNREADABLE)"));
        line("FINGERPRINTS=" + fingerprints);
        return out.toString();
    }

    private final boolean[] detected = new boolean[6];
    private final java.util.List<String> suspiciousIfaces = new ArrayList<>();
    private final java.util.List<String> fingerprints = new ArrayList<>();
    private boolean routeReadable = true;
    private boolean ifInet6Readable = true;

    private String result(int index) {
        return detected[index] ? "YES" : "NO";
    }

    private void checkVpnTransport() {
        section("[1] NetworkCapabilities / TRANSPORT_VPN");
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = cm.getActiveNetwork();
            line("activeNetwork=" + active);
            NetworkCapabilities activeCaps = active == null ? null : cm.getNetworkCapabilities(active);
            if (activeCaps != null) {
                line("active.hasVpn=" + activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        + " hasWifi=" + activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        + " hasCellular=" + activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        + " hasEthernet=" + activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
                if (activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) detected[1] = true;
            }
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null) continue;
                boolean vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                if (vpn) detected[1] = true;
                LinkProperties lp = cm.getLinkProperties(network);
                line("network=" + network + " vpn=" + vpn
                        + " ifaces=" + (lp == null ? "?" : lp.getInterfaceName()));
            }
        } catch (Throwable t) {
            line("ERROR " + t);
        }
    }

    private void checkNetworkInterfaces() {
        section("[2] NetworkInterface.getNetworkInterfaces()");
        List<String> vpnLike = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) {
                line("null interface enumeration");
            } else {
                for (NetworkInterface nif : Collections.list(en)) {
                    StringBuilder addrs = new StringBuilder();
                    for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                        addrs.append(addr.getHostAddress()).append(" ");
                    }
                    boolean suspicious = VPN_LIKE.matcher(nif.getName()).find();
                    boolean p2p = nif.isPointToPoint();
                    if (suspicious) vpnLike.add(nif.getName());
                    if (suspicious || p2p) suspiciousIfaces.add(nif.getName());
                    String addrText = addrs.toString().trim();
                    if (p2p && nif.getMTU() == 9000) {
                        fingerprints.add(nif.getName() + ":mtu9000");
                    }
                    if (addrText.contains("fdfe:dcba:9876")) {
                        fingerprints.add(nif.getName() + ":mihomo-default-ula");
                    }
                    if (addrText.matches("(?s).*\\b198\\.1[89]\\..*")) {
                        fingerprints.add(nif.getName() + ":fake-ip-198.1x");
                    }
                    line(String.format(Locale.US, "iface=%-14s up=%-5s p2p=%-5s mtu=%-5d addrs=%s%s",
                            nif.getName(), nif.isUp(), p2p, nif.getMTU(), addrText,
                            suspicious ? "  <-- VPN-LIKE" : (p2p ? "  <-- POINT-TO-POINT" : "")));
                }
            }
        } catch (Throwable t) {
            line("ERROR " + t);
        }
        line("vpnLikeInterfaces=" + vpnLike);
        line("suspiciousInterfaces(name or p2p)=" + suspiciousIfaces);
        if (!suspiciousIfaces.isEmpty()) detected[2] = true;
    }

    private void checkRouteFiles() {
        section("[3] /proc/net/route & /proc/net/ipv6_route");
        checkRouteFile("/proc/net/route", 0);
        checkRouteFile("/proc/net/ipv6_route", 9);
    }

    private void checkRouteFile(String path, int ifaceField) {
        line("--- " + path + " ---");
        try {
            BufferedReader reader = new BufferedReader(new FileReader(path));
            String lineText;
            int count = 0;
            while ((lineText = reader.readLine()) != null) {
                count++;
                String[] parts = lineText.trim().split("\\s+");
                String iface = parts.length > ifaceField ? parts[ifaceField] : "";
                if (VPN_LIKE.matcher(iface).find() || suspiciousIfaces.contains(iface)) {
                    detected[3] = true;
                    line("SUSPICIOUS ROUTE: " + lineText);
                }
                if (count <= 40) line("  " + lineText);
            }
            reader.close();
            line("lines=" + count);
        } catch (Throwable t) {
            routeReadable = false;
            line("cannot read: " + t);
        }
    }

    private void checkLocalPorts() {
        section("[4] 127.0.0.1 proxy ports");
        for (int port : PORTS) {
            String tcp = probeTcp(port);
            line("tcp 127.0.0.1:" + port + " -> " + tcp);
            if ("CONNECTED".equals(tcp)) detected[4] = true;
        }
        String udp = probeUdpDns(1053);
        line("udp 127.0.0.1:1053 -> " + udp);
        if (udp.startsWith("REACHABLE")) detected[4] = true;
    }

    private String probeTcp(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            return "CONNECTED";
        } catch (ConnectException e) {
            return "REFUSED/REJECTED (" + e.getMessage() + ")";
        } catch (SocketTimeoutException e) {
            return "TIMEOUT";
        } catch (Throwable t) {
            return "ERROR " + t.getClass().getSimpleName();
        } finally {
            try { socket.close(); } catch (Throwable ignored) { }
        }
    }

    private String probeUdpDns(int port) {
        // 最小 DNS 查询：transaction=0x1234, flags=0x0100, qdcount=1，qname=example.com，type=A class=IN
        byte[] query = new byte[]{
                0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 0x03, 'c', 'o', 'm', 0x00,
                0x00, 0x01, 0x00, 0x01
        };
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.connect(InetAddress.getByName("127.0.0.1"), port);
            socket.setSoTimeout(1200);
            socket.send(new DatagramPacket(query, query.length));
            byte[] buffer = new byte[512];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            return "REACHABLE (got " + response.getLength() + " bytes)";
        } catch (PortUnreachableException e) {
            return "REJECTED (port unreachable)";
        } catch (SocketTimeoutException e) {
            return "TIMEOUT";
        } catch (Throwable t) {
            return "ERROR " + t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (socket != null) socket.close();
        }
    }

    private void checkIfInet6() {
        section("[5] /proc/net/if_inet6");
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/net/if_inet6"));
            String lineText;
            while ((lineText = reader.readLine()) != null) {
                String[] parts = lineText.trim().split("\\s+");
                String iface = parts.length > 5 ? parts[5] : "";
                if (VPN_LIKE.matcher(iface).find()) detected[5] = true;
                line("  " + lineText);
            }
            reader.close();
        } catch (Throwable t) {
            line("cannot read: " + t);
            ifInet6Readable = false;
        }
    }

    private void section(String title) {
        out.append("\n== ").append(title).append(" ==\n");
    }

    private void line(String text) {
        out.append(text).append('\n');
    }
}
