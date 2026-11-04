#!/system/bin/sh
set -eu
bb=/system/bin/busybox
test "$#" = 1
probe_hash=$1
test "${#probe_hash}" = 64
case "$probe_hash" in *[!0-9a-f]*) exit 20;; esac
candidate=/data/local/tmp/button-only-speech-20261104.odex
probe=/data/local/tmp/button-only-probe-20261104.jar
test "$($bb sha256sum "$candidate" | $bb cut -d ' ' -f 1)" = 00787efbd6edfe4a461c83f9c196e95b3c44108e11371baba8e4129b8d8f05cc
test "$($bb sha256sum "$probe" | $bb cut -d ' ' -f 1)" = "$probe_hash"
mkdir -p /data/local/tmp/button-only-probe-runtime/dalvik-cache
isolated=/data/local/tmp/button-only-probe-runtime
# API18 DexPathList ignores a raw .odex classpath entry. Pair the unchanged APK
# with the candidate beside it, matching the real preoptimized system layout.
cp /system/app/XCSpeechClient.apk "$isolated/XCSpeechClient.apk"
cp "$candidate" "$isolated/XCSpeechClient.odex"
test "$($bb sha256sum "$isolated/XCSpeechClient.apk" | $bb cut -d ' ' -f 1)" = ad20379bdbbc5a70f6f0448bd093c7c0bd1ef0a5241b85a6f71d5b692bc21011
test "$($bb sha256sum "$isolated/XCSpeechClient.odex" | $bb cut -d ' ' -f 1)" = 00787efbd6edfe4a461c83f9c196e95b3c44108e11371baba8e4129b8d8f05cc
ANDROID_DATA="$isolated" dalvikvm -Xverify:all -Xcheckdexsum -cp "$probe:$isolated/XCSpeechClient.apk" ButtonOnlyOdexProbe verified-button-only-candidate
