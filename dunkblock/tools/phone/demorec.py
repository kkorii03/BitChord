import sys, os, time, json, subprocess, threading
import cv2, numpy as np
# demorec.py <name> <out dir> [seconds per skill=16] [max skills=40] [skip rows=0]
# Practice court -> "수비 연습", skill list ("스킬 선택") open at its top, opponent already chosen. Records the game's own
# demonstration of EVERY skill in the list, one screen recording per skill (<name>_r<row>_c<col>.mp4: 2 demonstrations
# "시연 중", then practice reps "연습 중" with nobody pressing), plus <name>_r<row>.jpg (the icons and labels of that row)
# and <name>.log (json lines). No press is ever made by this script outside the skill panel.
#   list geometry (fractions of the landscape screen): icon columns x 0.105/0.175/0.245/0.315, panel y 0.27-0.555,
#   X (0.335, 0.24) toggles list <-> description of the picked skill. The panel is half transparent, icons are bright:
#   a row = a run of >= 0.045 H where >= 35 % of the pixels around the column centres have V > 135 (labels are shorter).
#   The list is scrolled half a row at a time and the real shift is measured by template matching, so every row gets an
#   absolute position and is recorded once; the script ends when the list no longer moves.
ADB = r"C:\Users\jang\Desktop\BitChord\tools\android-sdk\platform-tools\adb.exe"
COLS = (0.105, 0.175, 0.245, 0.315)
W, H = 3120, 1440

def adb(*a, raw=False, timeout=240):
    r = subprocess.run([ADB] + list(a), capture_output=True, timeout=timeout)
    return r.stdout if raw else r.stdout.decode("utf-8", "replace")

def shot():
    return cv2.imdecode(np.frombuffer(adb("exec-out", "screencap", "-p", raw=True), np.uint8), cv2.IMREAD_COLOR)

def tap(fx, fy): adb("shell", "input", "tap", str(int(fx * W)), str(int(fy * H)))

def rows(im):
    """[(centre y, height, [column has an icon] * 4)] of the icon rows in the panel, top first."""
    h, w = im.shape[:2]
    V = cv2.cvtColor(im, cv2.COLOR_BGR2HSV)[:, :, 2]
    bands = [(V[:, int((cx - 0.013) * w):int((cx + 0.013) * w)] > 135).mean(1) for cx in COLS]
    allb = np.max(np.stack(bands), 0)
    out = []; y0 = int(0.272 * h); y1 = int(0.553 * h); y = y0
    while y < y1:
        if allb[y] > 0.35:
            e = y
            while e < y1 and (allb[e] > 0.35 or (e + 2 < y1 and allb[e + 1] > 0.35 and allb[e + 2] > 0.35)): e += 1
            if e - y >= 0.04 * h:
                out.append(((y + e) / 2.0 / h, (e - y) / float(h), [float(b[y:e].mean()) > 0.3 for b in bands]))
            y = e
        else: y += 1
    return out

def shift(a, b):
    """how far (fraction of H) the list moved up between screenshots a and b."""
    h, w = a.shape[:2]
    x0, x1 = int(0.08 * w), int(0.34 * w)
    t = cv2.cvtColor(a[int(0.39 * h):int(0.55 * h), x0:x1], cv2.COLOR_BGR2GRAY)
    s = cv2.cvtColor(b[int(0.268 * h):int(0.55 * h), x0:x1], cv2.COLOR_BGR2GRAY)
    r = cv2.matchTemplate(s, t, cv2.TM_CCOEFF_NORMED)
    _, mx, _, loc = cv2.minMaxLoc(r)
    return (0.39 * h - (0.268 * h + loc[1])) / h, mx

def drag(px):
    """finger up by px (slow, no fling)."""
    y0 = 760; x = 624; n = 6
    cmd = "input motionevent DOWN %d %d; " % (x, y0)
    for i in range(1, n + 1): cmd += "input motionevent MOVE %d %d; " % (x, y0 - px * i // n)
    # the finger rests before it lifts: without it the list is flung about a whole row further (measured 11-11)
    cmd += "input motionevent MOVE %d %d; " % (x, y0 - px) * 4 + "input motionevent UP %d %d" % (x, y0 - px)
    adb("shell", cmd)

def rec(sec, out):
    p = "/sdcard/Download/_dbrec.mp4"
    adb("shell", "screenrecord", "--time-limit", str(sec), "--bit-rate", "6000000", "--size", "1560x720", p, timeout=sec + 60)
    time.sleep(0.8); adb("pull", p, out); adb("shell", "rm", p)

REF = [None]
def header(im):
    h, w = im.shape[:2]
    return cv2.cvtColor(im[int(0.222 * h):int(0.262 * h), int(0.072 * w):int(0.135 * w)], cv2.COLOR_BGR2GRAY)

def list_shown():
    """screenshot with the list showing; None if it cannot be reached. X toggles list ("스킬 선택") <-> description
    ("스킬 설명"): the header of the first screenshot (which must be the list) is the reference, same header = list
    (measured on 900-px screenshots: list 0.99+, description 0.86)."""
    for _ in range(3):
        im = shot()
        if REF[0] is None: REF[0] = header(im)
        if float(cv2.matchTemplate(header(im), REF[0], cv2.TM_CCOEFF_NORMED)[0][0]) > 0.95: return im, rows(im)
        tap(0.335, 0.24); time.sleep(1.2)
    return None, []

def main():
    name, out = sys.argv[1], sys.argv[2]
    sec = int(sys.argv[3]) if len(sys.argv) > 3 else 16
    mx = int(sys.argv[4]) if len(sys.argv) > 4 else 40
    skip = int(sys.argv[5]) if len(sys.argv) > 5 else 0      # rows already recorded by an earlier run: counted, not recorded
    os.makedirs(out, exist_ok=True)
    log = open(os.path.join(out, name + ".log"), "a", encoding="utf-8")
    def L(**k):
        k["clock"] = time.strftime("%H:%M:%S"); log.write(json.dumps(k, ensure_ascii=False) + "\n"); log.flush(); print(k, flush=True)
    S = 0.0; done = []; n = 0; still = 0
    im, r = list_shown()
    if im is None or not r: L(err="no skill list on screen"); return
    for page in range(30):
        for cy, hh, has in r:
            ay = cy + S
            if not (0.325 <= cy <= 0.525) or any(abs(ay - d) < 0.07 for d in done): continue
            done.append(ay); ri = len(done)
            if ri <= skip: continue
            hI, wI = im.shape[:2]
            cv2.imencode(".jpg", im[int(0.2 * hI):int(0.58 * hI), int(0.06 * wI):int(0.36 * wI)], [cv2.IMWRITE_JPEG_QUALITY, 88])[1].tofile(os.path.join(out, "%s_r%d.jpg" % (name, ri)))
            L(row=ri, y=round(cy, 3), abs=round(ay, 3), height=round(hh, 3), cols=has)
            for ci, ok in enumerate(has):
                if not ok or n >= mx: continue
                f = os.path.join(out, "%s_r%d_c%d.mp4" % (name, ri, ci + 1))
                th = threading.Thread(target=rec, args=(sec, f)); th.start()
                time.sleep(1.3); tap(COLS[ci], cy); th.join(); n += 1
                L(skill="r%d_c%d" % (ri, ci + 1), file=os.path.basename(f), bytes=os.path.getsize(f) if os.path.exists(f) else 0)
                tap(0.335, 0.24); time.sleep(1.1)
                im2, r2 = list_shown()
                if im2 is None: L(err="list lost after r%d_c%d" % (ri, ci + 1)); return
                sh, sc = shift(im, im2)
                if abs(sh) > 0.01 and sc > 0.6: L(warn="list moved by itself", shift=round(sh, 3)); S += sh; cy -= sh
                im = im2
        if n >= mx: break
        drag(170); time.sleep(0.9)
        im2, r2 = list_shown()
        if im2 is None: L(err="list lost after a scroll"); return
        sh, sc = shift(im, im2)
        L(page=page + 1, shift=round(sh, 3), score=round(sc, 2), rows=[round(q[0], 3) for q in r2])
        if sc < 0.5: L(warn="shift not measurable; assuming 0.105"); sh = 0.105
        S += sh; im, r = im2, r2
        still = still + 1 if sh < 0.012 else 0
        if still >= 2: break
    L(end=True, skills=n, rows=len(done))

main()
