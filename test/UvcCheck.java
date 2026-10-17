import xyz.rockheung.usbdiag.Uvc;

/** 호스트에서 도는 파서 자가검사: java -cp build/test:build/classes UvcCheck */
public class UvcCheck {
    static byte[] b(int... v) { byte[] o = new byte[v.length]; for (int i = 0; i < v.length; i++) o[i] = (byte) v[i]; return o; }

    public static void main(String[] a) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.writeBytes(b(18, 1, 0x00, 0x02, 0xef, 2, 1, 64, 0x5e, 0x34, 0x09, 0x21, 0x00, 0x01, 1, 2, 3, 1)); // device 345e:2109
        o.writeBytes(b(9, 2, 0, 0, 2, 1, 0, 0x80, 250));                    // config, 500mA
        o.writeBytes(b(9, 4, 1, 0, 0, 0x0e, 2, 0, 0));                      // VS if 1 alt 0
        o.writeBytes(b(11, 0x24, 0x06, 1, 1, 0, 1, 0, 0, 0, 0));            // FORMAT_MJPEG idx1
        o.writeBytes(b(34, 0x24, 0x07, 1, 0, 0x80, 0x07, 0x38, 0x04,        // FRAME_MJPEG 1920x1080
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0x15, 0x16, 0x05, 0x00, 2,                                   // default 333333 (30fps), 2 intervals
                0x15, 0x16, 0x05, 0x00, 0x2a, 0x2c, 0x0a, 0x00));            // 30, 15 fps
        o.writeBytes(b(27, 0x24, 0x04, 2, 1, 'Y', 'U', 'Y', '2', 0, 0, 0x10, 0, 0x80, 0, 0, 0xaa, 0, 0x38, 0x9b, 0x71, 16, 1, 0, 0, 0, 0)); // FORMAT_UNCOMPRESSED YUY2
        o.writeBytes(b(9, 4, 1, 1, 1, 0x0e, 2, 0, 0));                      // VS alt 1
        o.writeBytes(b(7, 5, 0x81, 0x05, 0x00, 0x14, 1));                   // iso in, 1024 x3
        String j = Uvc.describe(o.toByteArray());
        System.out.println(j);
        check(j, "\"vid\":\"345e\",\"pid\":\"2109\"");
        check(j, "\"bcdUSB\":\"2.00\"");
        check(j, "\"maxPower_mA\":500");
        check(j, "\"kind\":\"mjpeg\",\"index\":1");
        check(j, "\"w\":1920,\"h\":1080,\"defaultFps\":30.00,\"fps\":[30.00,15.00]");
        check(j, "\"fourcc\":\"YUY2\",\"bpp\":16");
        check(j, "\"type\":\"iso\",\"maxPacket\":1024,\"mult\":3");
        Uvc.Assembler as = new Uvc.Assembler();
        assert0(as.feed(b(2, 0x80, 'x', 'x'), 4));        // 중간부터 받은 프레임: 버림
        assert0(as.feed(b(2, 0x82, 'y', 'y'), 4));        // 그 프레임의 EOF: 아직 시작 전
        assert0(as.feed(b(2, 0x81, 'A', 'B'), 4));        // FID 1: 새 프레임 시작
        same(as.feed(b(2, 0x83, 'C', 'D'), 4), "ABCD");   // EOF 로 완성
        assert0(as.feed(b(2, 0x80, 'E', 'F'), 4));
        assert0(as.feed(b(2, 0x80, 'G', 'H'), 4));
        same(as.feed(b(2, 0x81, 'I', 'J'), 4), "EFGH");   // EOF 없이 FID 토글로 완성
        assert0(as.feed(b(2, 0xc1, 'K'), 3));             // ERR: IJ 프레임 버림
        if (as.errors != 1) throw new AssertionError("errors");
        if (!Uvc.isCompleteJpeg(b(0xff, 0xd8, 1, 2, 0xff, 0xd9))) throw new AssertionError("jpeg ok");
        if (!Uvc.isCompleteJpeg(b(0xff, 0xd8, 1, 2, 0xff, 0xd9, 0, 0))) throw new AssertionError("jpeg padded");
        if (Uvc.isCompleteJpeg(b(0xff, 0xd8, 1, 2, 3, 4))) throw new AssertionError("jpeg truncated");
        byte[] padded = new byte[300]; padded[0] = (byte) 0xff; padded[1] = (byte) 0xd8; padded[100] = (byte) 0xff; padded[101] = (byte) 0xd9;
        if (!Uvc.isCompleteJpeg(padded)) throw new AssertionError("jpeg long zero padding");
        System.out.println("OK");
    }

    static void assert0(byte[] f) { if (f != null) throw new AssertionError("unexpected frame " + new String(f)); }

    static void same(byte[] f, String want) {
        if (f == null || !new String(f).equals(want)) throw new AssertionError("want " + want + " got " + (f == null ? null : new String(f)));
    }

    static void check(String j, String want) {
        if (!j.contains(want)) throw new AssertionError("missing: " + want);
    }
}
