package xyz.rockheung.usbdiag;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * KVM 본체: UVC 캡처 스레드 하나가 최신 JPEG 를 모든 시청자에게 나눠 주고,
 * WebSocket 입력을 CH9329 패킷으로 감싸 CDC 시리얼로 보낸다.
 */
final class Kvm {
    private Kvm() {}

    // ---------------------------------------------------------------- 영상

    static final Object lock = new Object();
    static byte[] latest;
    static long seq, latestAt;
    static int viewers; // lock 으로 보호
    static volatile int wantFrame = 6, wantFps = 30; // 기본 1280x720 30fps
    static volatile String captureState = "stopped";
    private static Thread capture;

    static void ensureCapture(Context ctx) {
        synchronized (lock) {
            if (capture != null && capture.isAlive()) return;
            capture = new Thread(() -> captureLoop(ctx), "capture");
            capture.start();
        }
    }

    static boolean capturing() { Thread t = capture; return t != null && t.isAlive(); }

    /** afterSeq 보다 새 프레임을 기다린다. 시간 안에 없으면 null. */
    static byte[] waitFrame(long afterSeq, long timeoutMs, long[] seqOut) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (seq <= afterSeq) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) return null;
                lock.wait(left);
            }
            seqOut[0] = seq;
            return latest;
        }
    }

    static void captureLoop(Context ctx) {
        long idleSince = 0;
        while (true) {
            int frame = wantFrame, fps = wantFps;
            try {
                UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
                UsbDevice dev = null;
                UsbInterface vs = null;
                for (UsbDevice d : um.getDeviceList().values())
                    for (int k = 0; k < d.getInterfaceCount(); k++) {
                        UsbInterface it = d.getInterface(k);
                        if (it.getInterfaceClass() == 14 && it.getInterfaceSubclass() == 2 && it.getEndpointCount() > 0) { dev = d; vs = it; }
                    }
                if (dev == null || !um.hasPermission(dev)) throw new Exception(dev == null ? "no UVC device" : "no permission");
                UsbEndpoint ep = vs.getEndpoint(0);
                UsbDeviceConnection c = um.openDevice(dev);
                if (c == null) throw new Exception("openDevice failed");
                UsbRequest[] reqs = new UsbRequest[8];
                try {
                    if (!c.claimInterface(vs, true)) throw new Exception("claimInterface failed");
                    StringBuilder info = new StringBuilder();
                    byte[] cur = Main.uvcCommit(c, vs.getId(), 1, frame, fps, info);
                    int size = (int) Math.max(512, Math.min(Uvc.u32(cur, 22), 4 << 20)); // 정확히 maxPayload (Main.snap 주석 참고)
                    for (int k = 0; k < reqs.length; k++) {
                        reqs[k] = new UsbRequest();
                        reqs[k].initialize(c, ep);
                        ByteBuffer bb = ByteBuffer.allocate(size);
                        reqs[k].setClientData(bb);
                        reqs[k].queue(bb);
                    }
                    captureState = "running " + info;
                    Main.log("capture start: " + info);
                    byte[] buf = new byte[size];
                    Uvc.Assembler as = new Uvc.Assembler();
                    while (frame == wantFrame && fps == wantFps) {
                        int v;
                        synchronized (lock) { v = viewers; }
                        if (v > 0) idleSince = 0;
                        else if (idleSince == 0) idleSince = System.currentTimeMillis();
                        else if (System.currentTimeMillis() - idleSince > 10_000) { captureState = "stopped (idle)"; return; }
                        UsbRequest r;
                        try { r = c.requestWait(1000); } catch (java.util.concurrent.TimeoutException e) { continue; }
                        if (r == null) throw new Exception("requestWait failed (detached?)");
                        ByteBuffer bb = (ByteBuffer) r.getClientData();
                        int n = bb.position();
                        bb.flip();
                        bb.get(buf, 0, n);
                        bb.clear();
                        r.queue(bb);
                        byte[] f = as.feed(buf, n);
                        if (f == null || !Uvc.isCompleteJpeg(f)) continue;
                        synchronized (lock) {
                            latest = f;
                            latestAt = System.currentTimeMillis();
                            seq++;
                            lock.notifyAll();
                        }
                    }
                } finally {
                    for (UsbRequest r : reqs) if (r != null) { r.cancel(); r.close(); }
                    c.controlTransfer(0x02, 0x01, 0, ep.getAddress(), null, 0, 1000);
                    c.releaseInterface(vs);
                    c.close();
                }
            } catch (Exception e) {
                captureState = "retrying: " + e.getMessage();
                Main.log("capture: " + e.getMessage());
                synchronized (lock) { if (viewers == 0) { captureState = "stopped"; return; } }
                try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    static void serveMjpeg(Context ctx, Socket s) throws Exception {
        synchronized (lock) { viewers++; }
        try {
            ensureCapture(ctx);
            OutputStream o = s.getOutputStream();
            o.write(("HTTP/1.0 200 OK\r\nCache-Control: no-store\r\nConnection: close\r\n"
                    + "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            long[] last = {0};
            synchronized (lock) { // 막 받은 프레임이면 바로 보여 주고, 낡았으면(캡처가 죽어 있으면) 보내지 않는다
                last[0] = System.currentTimeMillis() - latestAt < 1000 ? Math.max(0, seq - 1) : seq;
            }
            int misses = 0;
            while (true) {
                byte[] f = waitFrame(last[0], 5000, last);
                if (f == null) {
                    // 10초 동안 새 프레임이 없으면 닫는다. 브라우저 <img> 가 오류를 받고 다시 붙는다(멈춘 화면 방지)
                    if (++misses >= 2) return;
                    ensureCapture(ctx);
                    continue;
                }
                misses = 0;
                o.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                o.write(f);
                o.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                o.flush(); // 느린 시청자는 그동안 지나간 프레임을 건너뛴다
            }
        } finally {
            synchronized (lock) { viewers--; }
        }
    }

    // ---------------------------------------------------------------- 입력

    private static UsbDeviceConnection ser;
    private static UsbInterface serComm, serData;
    private static UsbEndpoint serOut, serIn;

    private static void serialOpen(Context ctx) throws Exception {
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        for (UsbDevice d : um.getDeviceList().values()) {
            UsbInterface comm = null, data = null;
            for (int k = 0; k < d.getInterfaceCount(); k++) {
                UsbInterface it = d.getInterface(k);
                if (it.getInterfaceClass() == 2 && it.getInterfaceSubclass() == 2) comm = it;
                if (it.getInterfaceClass() == 10) data = it;
            }
            if (comm == null || data == null) continue;
            if (!um.hasPermission(d)) throw new Exception("serial: no permission");
            UsbDeviceConnection c = um.openDevice(d);
            if (c == null) throw new Exception("serial: openDevice failed");
            c.claimInterface(comm, true);
            c.claimInterface(data, true);
            byte[] lineCoding = {(byte) 0x00, (byte) 0xe1, 0x00, 0x00, 0, 0, 8}; // 57600, 1 stop, no parity, 8 bits
            c.controlTransfer(0x21, 0x20, 0, comm.getId(), lineCoding, 7, 1000); // SET_LINE_CODING
            c.controlTransfer(0x21, 0x22, 0x03, comm.getId(), null, 0, 1000);     // SET_CONTROL_LINE_STATE: DTR|RTS
            for (int e = 0; e < data.getEndpointCount(); e++) {
                UsbEndpoint ep = data.getEndpoint(e);
                if (ep.getDirection() == 0) serOut = ep; else serIn = ep;
            }
            ser = c;
            serComm = comm;
            serData = data;
            Main.log(String.format(Locale.US, "serial open %04x:%04x", d.getVendorId(), d.getProductId()));
            return;
        }
        throw new Exception("serial: no CDC device");
    }

    private static void serialClose() {
        if (ser == null) return;
        ser.releaseInterface(serData);
        ser.releaseInterface(serComm);
        ser.close();
        ser = null;
    }

    /** 실패하면 한 번 다시 열어 본다(재연결 대비). */
    static synchronized void send(Context ctx, int cmd, byte[] data) throws Exception {
        byte[] p = Ch9329.packet(cmd, data);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (ser == null) serialOpen(ctx);
            if (ser.bulkTransfer(serOut, p, p.length, 200) == p.length) return;
            serialClose();
        }
        throw new Exception("serial write failed");
    }

    static synchronized String hidInfo(Context ctx) {
        try {
            send(ctx, Ch9329.GET_INFO, new byte[0]);
            byte[] buf = new byte[256];
            int n = 0;
            long until = System.currentTimeMillis() + 500;
            while (System.currentTimeMillis() < until && n < buf.length) {
                byte[] tmp = new byte[64];
                int r = ser.bulkTransfer(serIn, tmp, tmp.length, 100);
                if (r > 0) { System.arraycopy(tmp, 0, buf, n, Math.min(r, buf.length - n)); n += r; }
                byte[] d = Ch9329.parse(buf, n);
                if (d != null) return Ch9329.info(d);
            }
            return "{\"error\":\"no response\",\"rx\":\"" + Uvc.hex(buf, 0, n) + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 브라우저 메시지: [1, modifier, 0, k1..k6] 키보드, [2, buttons, xL, xH, yL, yH, wheel] 절대 마우스(0..4095),
     * [3, buttons, dx, dy, wheel] 상대 마우스.
     */
    static void serveWs(Context ctx, Socket s, String key) throws Exception {
        OutputStream o = s.getOutputStream();
        o.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + Ws.accept(key) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        o.flush();
        s.setSoTimeout(0);
        s.setTcpNoDelay(true);
        InputStream in = s.getInputStream();
        Main.log("ws open " + s.getRemoteSocketAddress());
        try {
            while (true) {
                Ws.Msg m = Ws.read(in);
                if (m.opcode == 8) return;
                if (m.opcode == 9) { Ws.write(o, 10, m.data); continue; }
                if (m.opcode != 2 || m.data.length < 1) continue;
                byte[] d = m.data;
                int t = d[0];
                if (t == 1 && d.length == 9) send(ctx, Ch9329.KEYBOARD, slice(d, 1, 8));
                else if (t == 2 && d.length == 7) send(ctx, Ch9329.MOUSE_ABS, prefix(0x02, slice(d, 1, 6)));
                else if (t == 3 && d.length == 5) send(ctx, Ch9329.MOUSE_REL, prefix(0x01, slice(d, 1, 4)));
            }
        } finally {
            // 연결이 끊기면 눌린 키와 버튼을 모두 뗀다
            try { send(ctx, Ch9329.KEYBOARD, new byte[8]); } catch (Exception ignored) {}
            Main.log("ws closed " + s.getRemoteSocketAddress());
        }
    }

    static byte[] slice(byte[] b, int off, int len) { byte[] o = new byte[len]; System.arraycopy(b, off, o, 0, len); return o; }

    static byte[] prefix(int first, byte[] rest) { byte[] o = new byte[rest.length + 1]; o[0] = (byte) first; System.arraycopy(rest, 0, o, 1, rest.length); return o; }
}
