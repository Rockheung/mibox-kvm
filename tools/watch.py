#!/usr/bin/env python3
"""KVM 복구 감시: MJPEG 스트림을 계속 받으며 프레임 공백, 재접속, /hid 상태를 기록한다.
사용: python3 -I tools/watch.py 192.168.88.194:8080"""
import json, socket, sys, threading, time, urllib.request

host = sys.argv[1]
t0 = time.time()
def log(*a): print(f"{time.strftime('%H:%M:%S')} +{time.time()-t0:7.1f}s", *a, flush=True)

def hid_loop():
    last = None
    while True:
        try:
            j = urllib.request.urlopen(f"http://{host}/hid", timeout=3).read().decode().strip()
        except Exception as e:
            j = f"ERR {type(e).__name__}"
        if j != last: log("hid", j); last = j
        time.sleep(2)

def stream_loop():
    while True:
        try:
            h, p = host.split(":")
            s = socket.create_connection((h, int(p)), 5); s.settimeout(5)
            s.sendall(b"GET /stream.mjpeg HTTP/1.0\r\n\r\n")
            buf, frames, last, t_conn = b"", 0, time.time(), time.time()
            log("stream connected")
            while True:
                d = s.recv(65536)
                if not d: raise EOFError("closed")
                buf += d
                while True:
                    i = buf.find(b"Content-Length: ")
                    if i < 0: break
                    j = buf.find(b"\r\n\r\n", i)
                    if j < 0: break
                    n = int(buf[i+16:buf.find(b"\r\n", i)])
                    if len(buf) < j + 4 + n: break
                    buf = buf[j + 4 + n:]
                    now = time.time(); frames += 1
                    if frames == 1: log(f"first frame after {now-t_conn:.1f}s")
                    elif now - last > 1.0: log(f"gap {now-last:.1f}s")
                    last = now
                    if frames % 300 == 0: log(f"{frames} frames")
        except Exception as e:
            log("stream lost:", type(e).__name__, e)
            time.sleep(1)

threading.Thread(target=hid_loop, daemon=True).start()
stream_loop()
