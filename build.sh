#!/bin/sh
# Gradle 없이 빌드: aapt2 → javac → d8 → zipalign → apksigner. 결과 build/usbdiag.apk
set -e
cd "$(dirname "$0")"
export JAVA_HOME="$HOME/opt/jdk17" PATH="$HOME/opt/jdk17/bin:$PATH"
BT="$HOME/opt/android-sdk/build-tools/34.0.0"
JAR="$HOME/opt/android-sdk/platforms/android-28/android.jar"

rm -rf build && mkdir -p build/classes build/test build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link build/res.zip -I "$JAR" --manifest AndroidManifest.xml -A assets -o build/unsigned.apk

javac -Xlint:-options --release 8 -classpath "$JAR" -d build/classes $(find src -name '*.java')
javac -cp build/classes -d build/test test/UvcCheck.java
java -cp build/test:build/classes UvcCheck >/dev/null && echo "UvcCheck OK"

"$BT/d8" --lib "$JAR" --min-api 26 --output build/dex $(find build/classes -name '*.class')
python3 -I -c 'import sys,zipfile; zipfile.ZipFile(sys.argv[1],"a").write(sys.argv[2],"classes.dex")' build/unsigned.apk build/dex/classes.dex
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

# 서명 키가 OTA 의 신뢰 근거다. 이 파일을 잃으면 기존 설치본은 OTA 로 갱신할 수 없다. 백업할 것.
KS="${KS:-$HOME/.android/usbdiag.keystore}"
KS_PASS="${KS_PASS:-android}"
[ -f "$KS" ] || { mkdir -p "$HOME/.android"; keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias diag -keyalg RSA -keysize 2048 -validity 10000 -dname CN=usbdiag >/dev/null 2>&1; }
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --out build/usbdiag.apk build/aligned.apk
echo "built build/usbdiag.apk"
