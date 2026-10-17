package xyz.rockheung.usbdiag;

/**
 * NanoKVM USB 의 키보드·마우스 칩 프로토콜(CH9329 호환, 57600 8N1).
 * 패킷: 57 AB | ADDR | CMD | LEN | DATA... | SUM(앞 바이트 합의 하위 8비트). 응답의 CMD 는 요청 | 0x80.
 * 참고: sipeed/NanoKVM-USB browser/src/libs/device/proto.ts
 */
public final class Ch9329 {
    private Ch9329() {}

    public static final int GET_INFO = 0x01, KEYBOARD = 0x02, MOUSE_ABS = 0x04, MOUSE_REL = 0x05;

    public static byte[] packet(int cmd, byte[] data) {
        byte[] p = new byte[6 + data.length];
        p[0] = 0x57;
        p[1] = (byte) 0xab;
        p[2] = 0x00;
        p[3] = (byte) cmd;
        p[4] = (byte) data.length;
        System.arraycopy(data, 0, p, 5, data.length);
        int sum = 0;
        for (int i = 0; i < p.length - 1; i++) sum += p[i] & 0xff;
        p[p.length - 1] = (byte) sum;
        return p;
    }

    /** 받은 바이트에서 체크섬이 맞는 첫 응답 패킷의 DATA 를 꺼낸다. 아직 덜 왔으면 null. */
    public static byte[] parse(byte[] b, int n) {
        for (int h = 0; h + 6 <= n; h++) {
            if ((b[h] & 0xff) != 0x57 || (b[h + 1] & 0xff) != 0xab) continue;
            int len = b[h + 4] & 0xff;
            if (h + 6 + len > n) return null;
            int sum = 0;
            for (int i = h; i < h + 5 + len; i++) sum += b[i] & 0xff;
            if ((sum & 0xff) != (b[h + 5 + len] & 0xff)) continue;
            byte[] d = new byte[len];
            System.arraycopy(b, h + 5, d, 0, len);
            return d;
        }
        return null;
    }

    /** GET_INFO 응답: [버전(0x30+), 대상 PC USB 연결, LED 비트(Num/Caps/Scroll), ...] */
    public static String info(byte[] d) {
        if (d == null || d.length < 3) return "{\"error\":\"no response\"}";
        return String.format("{\"chip\":\"V%.1f\",\"targetConnected\":%b,\"numLock\":%b,\"capsLock\":%b,\"scrollLock\":%b}",
                1.0 + ((d[0] & 0xff) - 0x30) / 10.0, d[1] != 0, (d[2] & 1) != 0, (d[2] & 2) != 0, (d[2] & 4) != 0);
    }
}
