package xyz.rockheung.usbdiag;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** 최소 WebSocket(RFC 6455) 서버 쪽. 조각난 메시지(continuation)는 쓰지 않는다고 가정한다. */
public final class Ws {
    private Ws() {}

    public static String accept(String key) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-1")
                .digest((key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(h);
    }

    /** 메시지 하나. opcode 는 1 text, 2 binary, 8 close, 9 ping, 10 pong. */
    public static final class Msg {
        public final int opcode;
        public final byte[] data;
        Msg(int opcode, byte[] data) { this.opcode = opcode; this.data = data; }
    }

    public static Msg read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        int b0 = d.readUnsignedByte(), b1 = d.readUnsignedByte();
        long len = b1 & 0x7f;
        if (len == 126) len = d.readUnsignedShort();
        else if (len == 127) len = d.readLong();
        if (len > 1 << 20) throw new IOException("frame too large: " + len);
        byte[] mask = new byte[4];
        if ((b1 & 0x80) != 0) d.readFully(mask);
        byte[] data = new byte[(int) len];
        d.readFully(data);
        if ((b1 & 0x80) != 0) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
        return new Msg(b0 & 0x0f, data);
    }

    public static void write(OutputStream out, int opcode, byte[] data) throws IOException {
        int n = data.length;
        byte[] h = n < 126 ? new byte[]{(byte) (0x80 | opcode), (byte) n}
                : new byte[]{(byte) (0x80 | opcode), 126, (byte) (n >> 8), (byte) n}; // ponytail: 64KB 넘는 송신 없음
        out.write(h);
        out.write(data);
        out.flush();
    }
}
