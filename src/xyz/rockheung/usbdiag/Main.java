package xyz.rockheung.usbdiag;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.TextView;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Mi Box 에서 USB 장치 디스크립터를 HTTP 로 내보내는 진단 앱.
 * USB 디버깅을 끄면 ADB 가 사라지므로 상태는 전부 :8080 으로 본다.
 *   GET /usb   장치 목록 + 권한 있으면 raw 디스크립터 파싱
 *   GET /perm  권한 없는 장치마다 권한 대화상자 띄우기 (TV 화면에서 허용)
 *   GET /log   attach/detach/권한 이벤트
 */
public final class Main {
    static final int PORT = 8080;
    static final String ACTION_PERM = "xyz.rockheung.usbdiag.PERM";
    static final ArrayDeque<String> LOG = new ArrayDeque<>();

    static synchronized void log(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + s;
        LOG.addLast(line);
        while (LOG.size() > 500) LOG.removeFirst(); // ponytail: 메모리 링버퍼, 재시작하면 사라진다
    }

    static synchronized String logText() { return String.join("\n", LOG) + "\n"; }

    static String q(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    static String ips() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a.getHostAddress());
        } catch (Exception ignored) {}
        return String.join(", ", out);
    }

    static String usbJson(Context ctx) {
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        List<String> devs = new ArrayList<>();
        for (UsbDevice d : um.getDeviceList().values()) {
            boolean perm = um.hasPermission(d);
            StringBuilder s = new StringBuilder();
            s.append(String.format(Locale.US, "{\"name\":%s,\"vid\":\"%04x\",\"pid\":\"%04x\",\"class\":%d,\"sub\":%d,\"manufacturer\":%s,\"product\":%s,\"permission\":%b,\"interfaces\":[",
                    q(d.getDeviceName()), d.getVendorId(), d.getProductId(), d.getDeviceClass(), d.getDeviceSubclass(),
                    q(d.getManufacturerName()), q(d.getProductName()), perm));
            for (int k = 0; k < d.getInterfaceCount(); k++) {
                UsbInterface it = d.getInterface(k);
                if (k > 0) s.append(',');
                s.append(String.format(Locale.US, "{\"id\":%d,\"alt\":%d,\"class\":%d,\"sub\":%d,\"proto\":%d,\"name\":%s,\"eps\":[",
                        it.getId(), it.getAlternateSetting(), it.getInterfaceClass(), it.getInterfaceSubclass(),
                        it.getInterfaceProtocol(), q(it.getName())));
                for (int e = 0; e < it.getEndpointCount(); e++) {
                    UsbEndpoint ep = it.getEndpoint(e);
                    if (e > 0) s.append(',');
                    s.append(String.format(Locale.US, "{\"addr\":\"%02x\",\"dir\":\"%s\",\"type\":%d,\"maxPacket\":%d}",
                            ep.getAddress(), ep.getDirection() == UsbConstants.USB_DIR_IN ? "in" : "out",
                            ep.getType(), ep.getMaxPacketSize()));
                }
                s.append("]}");
            }
            s.append("],\"raw\":");
            if (perm) {
                UsbDeviceConnection c = um.openDevice(d);
                byte[] raw = c == null ? null : c.getRawDescriptors();
                if (c != null) c.close();
                s.append(raw == null ? "null" : Uvc.describe(raw));
                if (raw != null) s.append(",\"rawHex\":").append(q(Uvc.hex(raw, 0, raw.length)));
            } else {
                s.append("null");
            }
            s.append('}');
            devs.add(s.toString());
        }
        return "{\"ips\":" + q(ips()) + ",\"devices\":[" + String.join(",", devs) + "]}\n";
    }

    static String requestPerms(Context ctx) {
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        int n = 0;
        for (UsbDevice d : um.getDeviceList().values()) {
            if (um.hasPermission(d)) continue;
            Intent i = new Intent(ACTION_PERM).setPackage(ctx.getPackageName());
            um.requestPermission(d, PendingIntent.getBroadcast(ctx, d.getDeviceId(), i, 0));
            n++;
        }
        log("permission requested for " + n + " device(s), camera="
                + (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED));
        return "requested " + n + " (TV 화면에서 허용)\n";
    }

    static long le32(byte[] b, int i) { return Uvc.u32(b, i); }

    static void put32(byte[] b, int i, long v) { for (int k = 0; k < 4; k++) b[i + k] = (byte) (v >> (8 * k)); }

    static int qp(String path, String key, int def) {
        int q = path.indexOf('?');
        if (q < 0) return def;
        for (String kv : path.substring(q + 1).split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && p[0].equals(key)) try { return Integer.parseInt(p[1]); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    /**
     * UVC PROBE/COMMIT 으로 포맷·해상도를 정하고 bulk 엔드포인트에서 프레임 한 장을 받는다.
     * fmt/frame 은 /usb 에 나온 format index, frame index. 기본은 MJPEG 640x480 30fps.
     */
    /** UVC PROBE/COMMIT. 장치가 정한 값(GET_CUR)을 돌려준다: [2] format, [3] frame, [4..7] interval, [18..21] maxFrame, [22..25] maxPayload */
    static byte[] uvcCommit(UsbDeviceConnection c, int idx, int fmt, int frame, int fps, StringBuilder info) throws Exception {
        byte[] probe = new byte[26]; // UVC 1.0 (bcdUVC 0x0100) 의 probe 길이
        probe[0] = 1; // bmHint: dwFrameInterval 고정
        probe[2] = (byte) fmt;
        probe[3] = (byte) frame;
        put32(probe, 4, 10_000_000L / fps);
        int r1 = c.controlTransfer(0x21, 0x01, 0x0100, idx, probe, probe.length, 1000); // SET_CUR PROBE
        byte[] cur = new byte[26];
        int r2 = c.controlTransfer(0xa1, 0x81, 0x0100, idx, cur, cur.length, 1000);     // GET_CUR PROBE
        int r3 = c.controlTransfer(0x21, 0x01, 0x0200, idx, cur, cur.length, 1000);     // SET_CUR COMMIT
        info.append(String.format(Locale.US, "probe set=%d get=%d commit=%d fmt=%d frame=%d interval=%d maxFrame=%d maxPayload=%d",
                r1, r2, r3, cur[2], cur[3], le32(cur, 4), le32(cur, 18), le32(cur, 22)));
        if (r3 < 0) throw new Exception("commit failed: " + info);
        return cur;
    }

    /** benchMs > 0 이면 그동안 받기만 하고 통계를 info 에 남긴 뒤 null 을 돌려준다. */
    static byte[] snap(Context ctx, int fmt, int frame, int fps, int skip, int queue, int reqSize, int benchMs,
                       StringBuilder info) throws Exception {
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        UsbDevice dev = null;
        UsbInterface vs = null;
        for (UsbDevice d : um.getDeviceList().values())
            for (int k = 0; k < d.getInterfaceCount(); k++) {
                UsbInterface it = d.getInterface(k);
                if (it.getInterfaceClass() == 14 && it.getInterfaceSubclass() == 2 && it.getEndpointCount() > 0) { dev = d; vs = it; }
            }
        if (dev == null) throw new Exception("no UVC streaming interface");
        if (!um.hasPermission(dev)) throw new Exception("no permission, call /perm");
        UsbEndpoint ep = vs.getEndpoint(0);
        UsbDeviceConnection c = um.openDevice(dev);
        if (c == null) throw new Exception("openDevice failed");
        try {
            if (!c.claimInterface(vs, true)) throw new Exception("claimInterface failed");
            byte[] cur = uvcCommit(c, vs.getId(), fmt, frame, fps, info);
            long maxPayload = le32(cur, 22);

            // 동기 bulkTransfer 하나씩으로는 고해상도에서 페이로드를 놓친다. 요청 여러 개를 미리 걸어 둔다.
            // 요청 크기는 정확히 dwMaxPayloadTransferSize 여야 한다. 15360 은 512 의 배수라 페이로드 끝에 short packet 이 없어서,
            // 더 크게 잡으면 여러 페이로드가 한 전송으로 합쳐지고 중간 헤더(12B)가 JPEG 안에 섞인다.
            int size = reqSize > 0 ? reqSize : (int) Math.max(512, Math.min(maxPayload, 4 << 20));
            UsbRequest[] reqs = new UsbRequest[queue];
            for (int k = 0; k < queue; k++) {
                reqs[k] = new UsbRequest();
                reqs[k].initialize(c, ep);
                ByteBuffer bb = ByteBuffer.allocate(size);
                reqs[k].setClientData(bb);
                reqs[k].queue(bb);
            }
            try {
                byte[] buf = new byte[size];
                Uvc.Assembler as = new Uvc.Assembler();
                long t0 = System.currentTimeMillis(), tFirst = 0, bytes = 0;
                int good = 0, truncated = 0, maxN = 0;
                long goodBytes = 0;
                int[] hist = new int[5]; // 페이로드 크기: <1K <4K <16K <64K 그 이상
                String truncTail = null;
                long limit = benchMs > 0 ? benchMs : 5000;
                while (System.currentTimeMillis() - t0 < limit) {
                    UsbRequest r;
                    try { r = c.requestWait(1000); } catch (java.util.concurrent.TimeoutException e) { continue; }
                    ByteBuffer bb = (ByteBuffer) r.getClientData();
                    int n = bb.position();
                    bb.flip();
                    bb.get(buf, 0, n);
                    bb.clear();
                    r.queue(bb);
                    bytes += n;
                    maxN = Math.max(maxN, n);
                    hist[n < 1024 ? 0 : n < 4096 ? 1 : n < 16384 ? 2 : n < 65536 ? 3 : 4]++;
                    byte[] f = as.feed(buf, n);
                    if (f == null) continue;
                    if (!Uvc.isCompleteJpeg(f)) {
                        if (truncated++ == 0) truncTail = f.length + "B tail=" + Uvc.hex(f, Math.max(0, f.length - 32), 32);
                        continue;
                    }
                    goodBytes += f.length;
                    if (good++ == 0) tFirst = System.currentTimeMillis();
                    if (benchMs > 0) continue;
                    if (good > skip) {
                        long span = System.currentTimeMillis() - tFirst;
                        info.append(String.format(Locale.US, " | %d bytes jpeg after %dms, good=%d truncated=%d payloads=%d errors=%d rx=%d, %.1f fps",
                                f.length, System.currentTimeMillis() - t0, good, truncated, as.payloads, as.errors, bytes,
                                span > 0 ? (good - 1) * 1000.0 / span : 0));
                        return f;
                    }
                }
                if (benchMs > 0) {
                    double secs = (System.currentTimeMillis() - t0) / 1000.0;
                    info.append(String.format(Locale.US, " | bench %.1fs queue=%d req=%d: rx=%.2fMB/s payloads=%d (<1K %d, <4K %d, <16K %d, <64K %d, more %d, max %d) errors=%d good=%d (%.1f fps, avg %dB) truncated=%d first=%s",
                            secs, queue, size, bytes / secs / 1e6, as.payloads, hist[0], hist[1], hist[2], hist[3], hist[4], maxN,
                            as.errors, good, good / secs, good > 0 ? goodBytes / good : 0, truncated, truncTail));
                    return null;
                }
                throw new Exception("timeout: good=" + good + " truncated=" + truncated + " payloads=" + as.payloads
                        + " errors=" + as.errors + " rx=" + bytes + " | " + info);
            } finally {
                for (UsbRequest r : reqs) { r.cancel(); r.close(); }
            }
        } finally {
            c.controlTransfer(0x02, 0x01, 0, ep.getAddress(), null, 0, 1000); // CLEAR_FEATURE(ENDPOINT_HALT): bulk 스트림 정지
            c.releaseInterface(vs);
            c.close();
        }
    }

    public static final class Svc extends Service {
        ServerSocket server;

        final BroadcastReceiver usbEvents = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                String id = d == null ? "?" : String.format("%04x:%04x %s", d.getVendorId(), d.getProductId(), d.getDeviceName());
                log(i.getAction().replace("android.hardware.usb.action.", "") + " " + id
                        + (ACTION_PERM.equals(i.getAction()) ? " granted=" + i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) : ""));
            }
        };

        @Override public void onCreate() {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("diag", "USB Diag", NotificationManager.IMPORTANCE_LOW));
            startForeground(1, new Notification.Builder(this, "diag")
                    .setContentTitle("USB Diag :" + PORT).setSmallIcon(android.R.drawable.stat_sys_upload).build());

            IntentFilter f = new IntentFilter();
            f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            f.addAction(ACTION_PERM);
            registerReceiver(usbEvents, f);

            new Thread(this::serve, "http").start();
            new Thread(() -> {
                try {
                    Thread.sleep(2 * 60_000L); // 부팅 직후 네트워크가 잡힐 시간을 준다
                    while (true) {
                        log(Update.check(this));
                        Thread.sleep(6 * 3600_000L); // ponytail: 고정 6시간 주기, 급한 배포는 /update 로
                    }
                } catch (InterruptedException ignored) {}
            }, "ota").start();
            log("service started, http://" + ips() + ":" + PORT);
        }

        void serve() {
            try {
                server = new ServerSocket(PORT);
                while (true) {
                    Socket s = server.accept();
                    new Thread(() -> handle(s), "http").start(); // ponytail: 연결마다 스레드, 시청자 수가 적다는 전제
                }
            } catch (Exception e) {
                log("server died: " + e);
            }
        }

        static String readLine(InputStream in) throws java.io.IOException {
            StringBuilder b = new StringBuilder();
            for (int c; (c = in.read()) >= 0 && c != '\n'; ) if (c != '\r') b.append((char) c);
            return b.toString();
        }

        void handle(Socket s) {
            try {
                s.setSoTimeout(5000);
                InputStream in = s.getInputStream();
                String line = readLine(in);
                String path = line.split(" ").length > 1 ? line.split(" ")[1] : "";
                String wsKey = null;
                for (String h; !(h = readLine(in)).isEmpty(); )
                    if (h.toLowerCase(Locale.US).startsWith("sec-websocket-key:")) wsKey = h.substring(18).trim();
                if (path.startsWith("/ws") && wsKey != null) { Kvm.serveWs(this, s, wsKey); return; }
                if (path.startsWith("/stream.mjpeg")) { s.setSoTimeout(0); Kvm.serveMjpeg(this, s); return; }

                String body = null, type = "text/plain; charset=utf-8";
                byte[] out = null;
                if (path.equals("/") || path.startsWith("/?") || path.startsWith("/index.html")) {
                    try (InputStream a = getAssets().open("index.html")) { out = Update.readAll(a).getBytes(StandardCharsets.UTF_8); }
                    type = "text/html; charset=utf-8";
                }
                else if (path.startsWith("/usb")) { body = usbJson(this); type = "application/json; charset=utf-8"; }
                else if (path.startsWith("/perm")) body = requestPerms(this);
                else if (path.startsWith("/log")) body = logText();
                else if (path.startsWith("/res")) {
                    Kvm.wantFrame = qp(path, "frame", Kvm.wantFrame);
                    Kvm.wantFps = qp(path, "fps", Kvm.wantFps);
                    body = "frame=" + Kvm.wantFrame + " fps=" + Kvm.wantFps + "\n";
                }
                else if (path.startsWith("/hid")) { body = Kvm.hidInfo(this) + "\n"; type = "application/json; charset=utf-8"; }
                else if (path.startsWith("/status"))
                    body = "capture=" + Kvm.captureState + " viewers=" + Kvm.viewers + " frame=" + Kvm.wantFrame + " fps=" + Kvm.wantFps + "\n";
                else if (path.startsWith("/bench")) {
                    StringBuilder info = new StringBuilder();
                    try {
                        if (Kvm.capturing()) throw new Exception("capture is running, close viewers first");
                        snap(this, qp(path, "fmt", 1), qp(path, "frame", 1), qp(path, "fps", 30), 0,
                                qp(path, "queue", 8), qp(path, "size", 0), qp(path, "secs", 10) * 1000, info);
                        body = info + "\n";
                    } catch (Exception e) {
                        body = "bench failed: " + e.getMessage() + "\n";
                    }
                    log(body.trim());
                }
                else if (path.startsWith("/snap")) {
                    StringBuilder info = new StringBuilder();
                    try {
                        if (Kvm.capturing()) { // 스트림이 돌고 있으면 장치를 뺏지 않고 최신 프레임을 준다
                            long[] seq = {0};
                            out = Kvm.waitFrame(0, 3000, seq);
                            if (out == null) throw new Exception("no frame from running capture");
                        } else {
                            out = snap(this, qp(path, "fmt", 1), qp(path, "frame", 11), qp(path, "fps", 30), qp(path, "skip", 5),
                                    qp(path, "queue", 8), qp(path, "size", 0), 0, info);
                            log("snap ok: " + info);
                        }
                        type = "image/jpeg";
                    } catch (Exception e) {
                        body = "snap failed: " + e.getMessage() + "\n";
                        log(body.trim());
                    }
                }
                else if (path.startsWith("/update")) { body = Update.check(this) + "\n"; log(body.trim()); }
                else if (path.startsWith("/version"))
                    body = "versionCode=" + Update.myVersion(this) + " deviceOwner=" + Update.deviceOwner(this) + " repo=" + Update.REPO + "\n";
                else body = "/ (KVM) /stream.mjpeg /ws /res?frame=6&fps=30 /hid /status /usb /perm /log /snap /bench /update /version\n";
                if (out == null) out = body.getBytes(StandardCharsets.UTF_8);
                OutputStream o = s.getOutputStream();
                o.write(("HTTP/1.0 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + out.length
                        + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                o.write(out);
            } catch (Exception e) {
                if (!(e instanceof java.net.SocketException)) log("http error: " + e);
            } finally {
                try { s.close(); } catch (Exception ignored) {}
            }
        }

        @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }
        @Override public IBinder onBind(Intent i) { return null; }
    }

    public static final class Ui extends Activity {
        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            startForegroundService(new Intent(this, Svc.class));
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 1);
            TextView t = new TextView(this);
            t.setTextSize(28);
            t.setPadding(64, 64, 64, 64);
            setContentView(t);
            onNewIntent(getIntent());
        }

        @Override protected void onNewIntent(Intent i) {
            super.onNewIntent(i);
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) log("opened via USB_DEVICE_ATTACHED");
            render();
        }

        @Override protected void onResume() { super.onResume(); render(); }

        void render() {
            TextView t = (TextView) ((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
            if (t != null) t.setText("USB Diag\nhttp://" + ips() + ":" + PORT + "/usb\n\n" + logText());
        }
    }

    public static final class Boot extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            c.startForegroundService(new Intent(c, Svc.class));
        }
    }
}
