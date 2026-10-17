# mibox-kvm

Xiaomi Mi Box S(MDZ-22-AB, Android TV 9)에 Sipeed NanoKVM USB를 꽂아 Wi-Fi IP-KVM으로 쓰려는 실험이다. 지금 들어 있는 것은 진단 앱(`USB Diag`) 하나다. Mi Box에서 USB 디스크립터를 읽고, UVC 프레임을 한 장 받고, 스스로 업데이트한다. ADB 없이도 HTTP로 상태를 볼 수 있게 만들었다.

## 확인된 사실

- **USB 디버깅을 켜면 USB 호스트가 죽는다.** Mi Box S의 USB-A 포트는 OTG 포트(dwc2)와 PHY를 나눠 쓴다. USB 디버깅을 켜면 init이 gadget을 UDC(`c9100000.dwc2_a`)에 바인딩하고, 그 순간 xhci 쪽 장치가 끊긴다. ADB를 Wi-Fi로 붙여도 마찬가지다. Android 9에는 gadget 없이 adbd만 TCP로 띄우는 방법이 없다. 그래서 개발 순서가 "디버깅 켜기 → 설치 → 디버깅 끄기 → HTTP로 확인"이 된다.
- NanoKVM USB는 USB 2.0에서 장치 두 개로 붙는다.
  - `345f:2131` MacroSilicon "USB2 Video": UVC 1.0, bulk 스트리밍(EP 0x83, 512B). MJPEG은 1920×1080@50fps부터 640×480@60fps까지, YUY2는 저프레임만 지원한다. UAC 오디오와 HID 인터페이스도 있다.
  - `1a86:55d3` WCH "USB Single Serial": CDC ACM. 키보드·마우스 칩은 CH9329 호환 프로토콜(57600 baud)을 쓴다. 자세한 내용은 [sipeed/NanoKVM-USB](https://github.com/sipeed/NanoKVM-USB)의 `browser/src/libs/device/proto.ts`에 있다.
- 영상 장치는 붙을 때마다 한 번 끊겼다가 1~2초 뒤 다시 붙는다.
- Android 9에서 UVC 장치에 USB 권한을 얻으려면 앱에 `CAMERA` 런타임 권한이 먼저 있어야 한다. 없으면 권한 요청이 대화상자 없이 바로 거부된다.
- userland가 32비트(`armeabi-v7a`)라서 arm64 전용 APK는 설치되지 않는다.

## 빌드

Gradle은 쓰지 않는다. JDK 17, Android build-tools 34, platform 28이 필요하다(`build.sh` 안의 경로 참고).

```sh
./build.sh          # build/usbdiag.apk, 파서 자가검사(test/UvcCheck.java) 포함
```

## HTTP 엔드포인트 (:8080)

| 경로 | 내용 |
|---|---|
| `/usb` | USB 장치, 인터페이스, 엔드포인트. 권한이 있으면 raw 디스크립터와 UVC 포맷/프레임 파싱 결과 |
| `/perm` | 권한 없는 장치마다 TV에 권한 대화상자를 띄운다 |
| `/snap?fmt=1&frame=11&fps=30` | UVC PROBE/COMMIT 후 MJPEG 한 장(`image/jpeg`). frame은 `/usb`의 frame index |
| `/bench?frame=1&secs=10&queue=8&size=0` | 일정 시간 받기만 하면서 처리량, 페이로드 크기 분포, 완전한/잘린 프레임 수, 첫 잘린 프레임의 꼬리를 보고한다. size=0이면 dwMaxPayloadTransferSize를 쓴다 |
| `/log` | attach/detach, 권한, OTA 이벤트 |
| `/update` | OTA 즉시 확인 |
| `/version` | versionCode, device owner 여부 |

인증이 없다. LAN이나 VPN 안에서만 열어 둘 것.

## OTA

- `release.sh`가 빌드하고, 서명하고, GitHub Release에 `usbdiag-<versionCode>.apk`로 올린다.
- 앱은 부팅 2분 뒤와 그 뒤 6시간마다 latest release를 확인한다. 더 높은 versionCode가 있으면 PackageInstaller로 설치한다.
- APK의 진위 검증은 Android 서명 검사가 맡는다. 다른 키로 서명된 APK나 다운그레이드는 OS가 거부한다. 그러니 서명 키스토어(`~/.android/usbdiag.keystore`)는 저장소 밖에 두고 백업해야 한다.
- 일반 앱은 무인 설치를 할 수 없어서 TV에 설치 확인 창이 뜬다. 앱이 device owner면 무인으로 설치된다(`Update$Admin`). 다만 `dpm set-device-owner`는 기기에 계정이 하나도 없을 때만 된다.
- 업데이트가 끝나면 `MY_PACKAGE_REPLACED`로 서비스가 다시 뜬다.

## 다음 할 일

연속 MJPEG 스트림, CDC 시리얼 입력 중계, NanoKVM-USB 웹 UI 포팅(시리얼 계층은 WebSocket으로, 카메라는 MJPEG `<img>`로 바꾼다), 상시 운용 검증.

## License

MIT
