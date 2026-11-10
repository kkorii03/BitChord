import sys, subprocess, os, time
# ph.py — drive the S24 over adb for unattended PRACTICE-COURT tests (never matches, purchases or consent screens of
# the game). Fractions are of the screen as it is shown now (the game is landscape 3120x1440; the adb rotation setting
# is never touched).
#   ph.py shot <out.jpg> [width]        screenshot, scaled (default 1040 px wide) so it is cheap to look at
#   ph.py tap <fx> <fy>                 tap at a fraction of the current screen
#   ph.py swipe <fx0> <fy0> <fx1> <fy1> [ms]
#   ph.py state                         top activity, screen/keyguard, battery, DunkBlock version + newest session
#   ph.py rec <seconds> <out.mp4>       screenrecord (<= 180 s), pulled to the PC and deleted on the phone
#   ph.py pull <session name> <dir>     pull one DunkBlock session folder
ADB = r"C:\Users\jang\Desktop\BitChord\tools\android-sdk\platform-tools\adb.exe"
SESS = "/sdcard/Android/data/dev.dunkblock.blocker/files/sessions"

def adb(*a, raw=False, timeout=240):
    r = subprocess.run([ADB] + list(a), capture_output=True, timeout=timeout)
    return r.stdout if raw else r.stdout.decode("utf-8", "replace")

def size():
    import re
    o = adb("shell", "dumpsys", "window", "displays")
    m = re.search(r"cur=(\d+)x(\d+)", o)
    return int(m.group(1)), int(m.group(2))

def main():
    c = sys.argv[1]
    if c == "shot":
        import cv2, numpy as np
        im = cv2.imdecode(np.frombuffer(adb("exec-out", "screencap", "-p", raw=True), np.uint8), cv2.IMREAD_COLOR)
        w = int(sys.argv[3]) if len(sys.argv) > 3 else 1040
        im2 = cv2.resize(im, (w, im.shape[0] * w // im.shape[1]), interpolation=cv2.INTER_AREA)
        cv2.imencode(".jpg", im2, [cv2.IMWRITE_JPEG_QUALITY, 82])[1].tofile(sys.argv[2])
        print("shot %dx%d -> %s" % (im.shape[1], im.shape[0], sys.argv[2]))
    elif c == "tap":
        W, H = size()
        x, y = int(float(sys.argv[2]) * W), int(float(sys.argv[3]) * H)
        adb("shell", "input", "tap", str(x), str(y)); print("tap", x, y, "of", W, H)
    elif c == "swipe":
        W, H = size(); a = [float(v) for v in sys.argv[2:6]]
        ms = sys.argv[6] if len(sys.argv) > 6 else "300"
        adb("shell", "input", "swipe", str(int(a[0] * W)), str(int(a[1] * H)), str(int(a[2] * W)), str(int(a[3] * H)), ms)
    elif c == "state":
        print(adb("shell", "dumpsys activity activities | grep topResumedActivity; dumpsys power | grep mWakefulness=;"
                  " dumpsys window | grep isKeyguardShowing; dumpsys battery | grep -E 'level|temperature|powered: true';"
                  " dumpsys package dev.dunkblock.blocker | grep versionCode; settings get secure enabled_accessibility_services;"
                  " ls -t " + SESS + " | head -3; date +%H:%M:%S"))
    elif c == "rec":
        sec = min(180, int(sys.argv[2])); p = "/sdcard/Download/_dbrec.mp4"
        adb("shell", "screenrecord", "--time-limit", str(sec), "--bit-rate", "6000000", "--size", "1560x720", p, timeout=sec + 60)
        time.sleep(1); adb("pull", p, sys.argv[3]); adb("shell", "rm", p); print("rec ->", sys.argv[3], os.path.getsize(sys.argv[3]))
    elif c == "pull":
        os.makedirs(sys.argv[3], exist_ok=True)
        print(adb("pull", SESS + "/" + sys.argv[2], sys.argv[3], timeout=900)[-300:])

main()
