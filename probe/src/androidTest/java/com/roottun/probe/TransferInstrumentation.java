package com.roottun.probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Process;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Local-only test driver. Open the socket in the real target package, not an adb shell or curl process. */
public final class TransferInstrumentation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle parameters) { super.onCreate(parameters); args = parameters; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            int port = Integer.parseInt(args.getString("source", "0"));
            String id = args.getString("requestId", "");
            String path = args.getString("path", "");
            if (port < 41000 || port > 48999 || !id.matches("[0-9a-f]{24}") || !(path.equals("/upload") || path.equals("/download"))) throw new IllegalArgumentException("invalid fixture arguments");
            if (Process.myUid() != getTargetContext().getApplicationInfo().uid || !getTargetContext().getPackageName().equals("com.roottun.probe")) throw new IllegalStateException("not target app UID");
            boolean upload = path.equals("/upload");
            final int uploadBytes = 4 * 1024 * 1024;
            byte[] block = new byte[16384];
            String headers = (upload ? "POST " : "GET ") + path + " HTTP/1.1\r\nHost: 10.0.2.2:38881\r\nConnection: close\r\nX-Fixture-Id: " + id + "\r\n" + (upload ? "Content-Type: application/octet-stream\r\nContent-Length: " + uploadBytes + "\r\n" : "") + "\r\n";
            try (Socket s = new Socket()) {
                s.setSoTimeout(12000);
                s.bind(new InetSocketAddress(port)); s.connect(new InetSocketAddress("10.0.2.2", 38881), 10000);
                OutputStream out = new java.io.BufferedOutputStream(s.getOutputStream()); out.write(headers.getBytes(StandardCharsets.US_ASCII));
                if (upload) {
                    for (int offset = 0; offset < uploadBytes; offset += block.length) {
                        for (int i = 0; i < block.length; i++) {
                            int pos = offset + i;
                            block[i] = (byte) (pos * 17 + (pos >>> 9));
                        }
                        out.write(block);
                    }
                }
                out.flush();
                InputStream in = s.getInputStream(); ByteArrayOutputStream response = new ByteArrayOutputStream(); byte[] chunk = new byte[8192];
                for (int n; (n = in.read(chunk)) != -1;) { if (response.size() + n > 150000) throw new IllegalStateException("response too large"); response.write(chunk, 0, n); }
                result.putString("response64", Base64.encodeToString(response.toByteArray(), Base64.NO_WRAP));
            }
            result.putString("uid", Integer.toString(Process.myUid())); finish(Activity.RESULT_OK, result);
        } catch (Exception e) { result.putString("failure", e.toString()); finish(Activity.RESULT_CANCELED, result); }
    }
}
