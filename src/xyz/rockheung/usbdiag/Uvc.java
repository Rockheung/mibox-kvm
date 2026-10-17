package xyz.rockheung.usbdiag;

import java.util.ArrayList;
import java.util.List;

/** Raw USB 설정 디스크립터를 훑어 인터페이스·엔드포인트·UVC 포맷/프레임을 JSON 으로 만든다. Android 의존 없음. */
public final class Uvc {
    private Uvc() {}

    static int u8(byte[] b, int i) { return b[i] & 0xff; }
    static int u16(byte[] b, int i) { return u8(b, i) | u8(b, i + 1) << 8; }
    static long u32(byte[] b, int i) { return (u16(b, i) | (long) u16(b, i + 2) << 16) & 0xffffffffL; }

    static String fourcc(byte[] b, int i) {
        StringBuilder s = new StringBuilder();
        for (int k = 0; k < 4; k++) {
            int c = u8(b, i + k);
            s.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? (char) c : '?');
        }
        return s.toString();
    }

    static String hex(byte[] b, int off, int len) {
        StringBuilder s = new StringBuilder();
        for (int k = off; k < off + len && k < b.length; k++) s.append(String.format("%02x", b[k]));
        return s.toString();
    }

    static String fps(long interval100ns) {
        return interval100ns == 0 ? "0" : String.format("%.2f", 1e7 / interval100ns);
    }

    /** 반환: {"device":{...},"items":[...]} */
    public static String describe(byte[] b) {
        List<String> items = new ArrayList<>();
        String device = "null";
        int curClass = -1, curSub = -1;
        int i = 0;
        while (i + 2 <= b.length) {
            int len = u8(b, i), type = u8(b, i + 1);
            if (len < 2 || i + len > b.length) {
                items.add("{\"error\":\"bad descriptor at " + i + "\",\"rest\":\"" + hex(b, i, b.length - i) + "\"}");
                break;
            }
            if (type == 0x01 && len >= 18) {
                device = String.format("{\"bcdUSB\":\"%x.%02x\",\"class\":%d,\"sub\":%d,\"proto\":%d,\"maxPacket0\":%d,\"vid\":\"%04x\",\"pid\":\"%04x\",\"bcdDevice\":\"%04x\",\"numConfigs\":%d}",
                        u8(b, i + 3), u8(b, i + 2), u8(b, i + 4), u8(b, i + 5), u8(b, i + 6), u8(b, i + 7),
                        u16(b, i + 8), u16(b, i + 10), u16(b, i + 12), u8(b, i + 17));
            } else if (type == 0x02 && len >= 9) {
                items.add(String.format("{\"t\":\"config\",\"totalLen\":%d,\"numIf\":%d,\"attrs\":\"%02x\",\"maxPower_mA\":%d}",
                        u16(b, i + 2), u8(b, i + 4), u8(b, i + 7), u8(b, i + 8) * 2));
            } else if (type == 0x0b && len >= 8) {
                items.add(String.format("{\"t\":\"iad\",\"firstIf\":%d,\"count\":%d,\"class\":%d,\"sub\":%d}",
                        u8(b, i + 2), u8(b, i + 3), u8(b, i + 4), u8(b, i + 5)));
            } else if (type == 0x04 && len >= 9) {
                curClass = u8(b, i + 5);
                curSub = u8(b, i + 6);
                items.add(String.format("{\"t\":\"if\",\"num\":%d,\"alt\":%d,\"numEp\":%d,\"class\":%d,\"sub\":%d,\"proto\":%d}",
                        u8(b, i + 2), u8(b, i + 3), u8(b, i + 4), curClass, curSub, u8(b, i + 7)));
            } else if (type == 0x05 && len >= 7) {
                int addr = u8(b, i + 2), attrs = u8(b, i + 3), mp = u16(b, i + 4);
                int size = mp & 0x7ff, mult = 1 + ((mp >> 11) & 3);
                String[] kinds = {"control", "iso", "bulk", "interrupt"};
                items.add(String.format("{\"t\":\"ep\",\"addr\":\"%02x\",\"dir\":\"%s\",\"type\":\"%s\",\"maxPacket\":%d,\"mult\":%d,\"interval\":%d}",
                        addr, (addr & 0x80) != 0 ? "in" : "out", kinds[attrs & 3], size, mult, u8(b, i + 6)));
            } else if (type == 0x24 && curClass == 0x0e && curSub == 2 && len >= 3) {
                items.add(vs(b, i, len));
            } else if (type == 0x24 && curClass == 0x0e && curSub == 1 && len >= 3) {
                items.add(String.format("{\"t\":\"vc\",\"subtype\":%d,\"hex\":\"%s\"}", u8(b, i + 2), hex(b, i, len)));
            } else {
                items.add(String.format("{\"t\":\"other\",\"type\":\"%02x\",\"hex\":\"%s\"}", type, hex(b, i, len)));
            }
            i += len;
        }
        return "{\"device\":" + device + ",\"items\":[" + String.join(",", items) + "]}";
    }

    /**
     * UVC bulk 페이로드(헤더 + 데이터)를 받아 프레임 단위로 묶는다.
     * 처음 FID 가 바뀌기 전까지는 중간부터 받은 프레임이라 버린다.
     */
    public static final class Assembler {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        private int lastFid = -1;
        private boolean started;
        public int payloads, errors;

        /**
         * 이전 전송이 요청 버퍼를 꽉 채워 끝났으면 같은 페이로드가 이어진다(bulk 규칙). 헤더 없이 붙인다.
         * 이 칩은 dwMaxPayloadTransferSize 를 넘는 페이로드(프레임 한 장)를 보내므로 꼭 필요하다.
         */
        public void feedContinuation(byte[] buf, int n) {
            if (started) out.write(buf, 0, n);
        }

        /** 완성된 프레임이 생기면 반환, 아니면 null */
        public byte[] feed(byte[] buf, int n) {
            if (n < 2) return null;
            int hl = u8(buf, 0), bfh = u8(buf, 1);
            if (hl < 2 || hl > n) return null;
            payloads++;
            int fid = bfh & 1;
            byte[] done = null;
            if (lastFid != -1 && fid != lastFid) {
                if (started && out.size() > 0) done = out.toByteArray();
                out.reset();
                started = true;
            }
            lastFid = fid;
            if ((bfh & 0x40) != 0) { // ERR: 이 프레임은 버린다
                errors++;
                out.reset();
                started = false;
                return done;
            }
            if (started) out.write(buf, hl, n - hl);
            if (started && (bfh & 2) != 0 && done == null) { // EOF
                done = out.toByteArray();
                out.reset();
            }
            return done;
        }
    }

    /** SOI(FFD8)로 시작하고, 끝의 00 패딩을 건너뛴 자리에 EOI(FFD9)가 있으면 완전한 JPEG 로 본다. */
    public static boolean isCompleteJpeg(byte[] f) {
        if (f.length < 4 || u8(f, 0) != 0xff || u8(f, 1) != 0xd8) return false;
        int i = f.length - 1;
        while (i > 3 && f[i] == 0) i--;
        return u8(f, i - 1) == 0xff && u8(f, i) == 0xd9;
    }

    /** UVC VideoStreaming class-specific 디스크립터 */
    static String vs(byte[] b, int i, int len) {
        int st = u8(b, i + 2);
        switch (st) {
            case 0x04: // FORMAT_UNCOMPRESSED
            case 0x10: // FORMAT_FRAME_BASED
                return String.format("{\"t\":\"format\",\"kind\":\"%s\",\"index\":%d,\"numFrames\":%d,\"fourcc\":\"%s\",\"bpp\":%d}",
                        st == 0x04 ? "uncompressed" : "frame-based", u8(b, i + 3), u8(b, i + 4), fourcc(b, i + 5), u8(b, i + 21));
            case 0x06: // FORMAT_MJPEG
                return String.format("{\"t\":\"format\",\"kind\":\"mjpeg\",\"index\":%d,\"numFrames\":%d}", u8(b, i + 3), u8(b, i + 4));
            case 0x05: // FRAME_UNCOMPRESSED
            case 0x07: // FRAME_MJPEG
            case 0x11: { // FRAME_FRAME_BASED: dwMaxVideoFrameBufferSize 가 없어서 4바이트 앞당겨진다
                boolean fb = st == 0x11;
                int defOff = fb ? 17 : 21, typeOff = fb ? 21 : 25, ivOff = 26;
                int w = u16(b, i + 5), h = u16(b, i + 7);
                long def = u32(b, i + defOff);
                int n = u8(b, i + typeOff);
                List<String> f = new ArrayList<>();
                if (n == 0 && len >= ivOff + 12) {
                    f.add("\"min " + fps(u32(b, i + ivOff + 4)) + "\"");
                    f.add("\"max " + fps(u32(b, i + ivOff)) + "\"");
                } else {
                    for (int k = 0; k < n && ivOff + 4 * k + 4 <= len; k++) f.add(fps(u32(b, i + ivOff + 4 * k)));
                }
                return String.format("{\"t\":\"frame\",\"index\":%d,\"w\":%d,\"h\":%d,\"defaultFps\":%s,\"fps\":[%s],\"maxBitRate\":%d}",
                        u8(b, i + 3), w, h, fps(def), String.join(",", f), u32(b, i + 13));
            }
            default:
                return String.format("{\"t\":\"vs\",\"subtype\":%d,\"hex\":\"%s\"}", st, hex(b, i, len));
        }
    }
}
