#!/bin/sh
# 빌드해서 GitHub Release 로 올린다. Mi Box 의 앱이 6시간마다(또는 /update) 이걸 받아 간다.
# 먼저 AndroidManifest.xml 의 versionCode 를 올리고 커밋·푸시할 것.
set -e
cd "$(dirname "$0")"
code=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' AndroidManifest.xml)
name=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
./build.sh
cp build/usbdiag.apk "build/usbdiag-$code.apk"
gh release create "v$name" "build/usbdiag-$code.apk" --title "v$name" --notes "versionCode $code" "$@"
