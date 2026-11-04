#!/system/bin/sh
# Install only a reviewed, fixed-size H53 speech ODEX; APK and app data remain intact.
set -eu
bb=/system/bin/busybox
target=/system/app/XCSpeechClient.odex
candidate=/data/local/tmp/button-only-speech-20261104.odex
backup=/system/button-only-speech-backup-20261104-v2
stage=/system/app/.XCSpeechClient.button-only-stage
original=84fa3b983da21d7c52d8703cea85efa467a4f70263eb0ff21b991026e35c4bd8
apk_original=ad20379bdbbc5a70f6f0448bd093c7c0bd1ef0a5241b85a6f71d5b692bc21011
test "$#" = 1
expected=$1
test "$expected" = 00787efbd6edfe4a461c83f9c196e95b3c44108e11371baba8e4129b8d8f05cc
test "${#expected}" = 64
case "$expected" in *[!0-9a-f]*) exit 20;; esac
test "$($bb id -u)" = 0
test "$(getprop ro.product.board)" = C3ALFUS
test "$(getprop ro.build.version.sdk)" = 18
test "$(getprop ro.build.display.id)" = 06.03.08600.H53.00060
test ! -L "$target" && test ! -L "$candidate"
test ! -e "$backup" && test ! -L "$backup" && test ! -e "$stage" && test ! -L "$stage"
test "$($bb sha256sum "$target" | $bb cut -d ' ' -f 1)" = "$original"
test "$($bb sha256sum /system/app/XCSpeechClient.apk | $bb cut -d ' ' -f 1)" = "$apk_original"
test "$($bb sha256sum "$candidate" | $bb cut -d ' ' -f 1)" = "$expected"
test "$($bb wc -c < "$candidate" | $bb tr -d ' ')" = 2070512
$bb grep -E ' /system [^ ]* ro(,| )' /proc/mounts >/dev/null
committed=0
replaced=0
cleanup() {
    result=$?
    trap - EXIT
    set +e
    if test "$committed" = 0 && test "$replaced" = 1; then
        # A mapped inode with zero links makes remount-ro fail with EBUSY.
        # Keep the candidate linked until its process has exited, too.
        held="$backup/held-before-failed-install-restore.odex"
        if test ! -e "$held" && test ! -L "$held" && ln "$target" "$held"; then
            cp "$backup/XCSpeechClient.odex" "$stage" && chmod 644 "$stage" && chown 0:0 "$stage" && mv "$stage" "$target"
            test "$($bb sha256sum "$target" | $bb cut -d ' ' -f 1)" = "$original" || result=91
        else
            result=94
        fi
        sync
    fi
    mount -o remount,ro /system || result=92
    $bb grep -E ' /system [^ ]* ro(,| )' /proc/mounts >/dev/null || result=93
    echo "INSTALL_EXIT:$result:COMMITTED:$committed"
    exit "$result"
}
trap cleanup EXIT
mount -o remount,rw /system
mkdir "$backup"
chmod 700 "$backup"
# Keep the original mapped inode linked during replacement. Directory 0700
# protects the backup without changing the original inode's 0644 permissions.
ln "$target" "$backup/XCSpeechClient.odex"
test "$($bb sha256sum "$backup/XCSpeechClient.odex" | $bb cut -d ' ' -f 1)" = "$original"
cp "$candidate" "$stage"
chmod 644 "$stage"
chown 0:0 "$stage"
test "$($bb sha256sum "$stage" | $bb cut -d ' ' -f 1)" = "$expected"
mv "$stage" "$target"
replaced=1
sync
test "$($bb sha256sum "$target" | $bb cut -d ' ' -f 1)" = "$expected"
test "$($bb sha256sum /system/app/XCSpeechClient.apk | $bb cut -d ' ' -f 1)" = "$apk_original"
committed=1
echo "ODEX_INSTALLED:$expected:RESTART_PENDING"
