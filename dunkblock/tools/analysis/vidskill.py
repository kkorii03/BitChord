import sys, os, csv, bisect, glob
import cv2, numpy as np
# vidskill.py <screen recording.mp4 | folder> ...  [-v]
# Learns from the game's own demonstrations in the practice court ("수비 연습": pick a skill -> 2 demonstrations with the
# banner "시연 중", then practice reps "연습 중"), recorded with `adb screenrecord` at 1560x720 (~118 fps), no app needed.
# Per frame (cached next to the video as <name>.scan.csv):
#   slot  = luma of the app's slot2 box (the button left-up of the block button) = what ShotWatcher.readSlot2 sees
#   btn   = mean luma of the block button box (+14..18 while the GAME presses it in a demonstration; not visible in a dark)
#   br,bg,bb = mean colour of the banner (top centre): blue "시연 중" / orange "연습 중"
#   burst = pixels >= 235 in the court area x 0.30-0.72, y 0.13-0.78 (block flash), counted on every 2nd pixel, x4
#   scr   = mean luma of that court area: signature skills ("선수 스킬") darken the whole screen, HUD included
# Per video one block of lines: banner segments, every DARK (onset, fully dark, fade-up start, end), every BURST, and
# what a slot-luma fade detector running at the app's 60 Hz would have seen (SIM: ms after the true onset).
FX, FY = 0.8733, 0.8418

def scan(p):
    o = p[:-4] + ".scan.csv"
    if os.path.exists(o) and os.path.getmtime(o) >= os.path.getmtime(p): return o
    v = cv2.VideoCapture(p); out = open(o, "w"); out.write("t,slot,btn,br,bg,bb,burst,scr\n")
    while True:
        ok, im = v.read()
        if not ok: break
        t = v.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
        h, w = im.shape[:2]
        cx, cy, r = int((FX - 0.09) * w), int((FY - 0.21) * h), int(0.03 * w)
        slot = cv2.cvtColor(im[cy - r:cy + r + 1, cx - r:cx + r + 1], cv2.COLOR_BGR2GRAY).mean()
        bx, by, br = int(FX * w), int(FY * h), int(0.05 * w)
        btn = cv2.cvtColor(im[by - br:by + br, bx - br:bx + br], cv2.COLOR_BGR2GRAY).mean()
        b_, g_, r_ = im[int(0.025 * h):int(0.07 * h), int(0.43 * w):int(0.56 * w)].reshape(-1, 3).mean(0)
        court = cv2.cvtColor(im[int(0.13 * h):int(0.78 * h):2, int(0.30 * w):int(0.72 * w):2], cv2.COLOR_BGR2GRAY)
        out.write("%.4f,%.1f,%.1f,%.0f,%.0f,%.0f,%d,%.1f\n" % (t, slot, btn, r_, g_, b_, int((court >= 235).sum()) * 4, court.mean()))
    out.close(); return o

def events(o, verbose=False):
    R = [(float(r["t"]), float(r["slot"]), float(r["btn"]), float(r["br"]), float(r["bg"]), float(r["bb"]), int(r["burst"]), float(r["scr"])) for r in csv.DictReader(open(o))]
    n = len(R); T = [r[0] for r in R]
    if n < 50: return None
    def med(a, b, col):
        i = bisect.bisect_left(T, a); j = bisect.bisect_left(T, b)
        v = sorted(r[col] for r in R[i:j]); return v[len(v) // 2] if v else None
    # banner by colour ratio (it darkens with the screen): D blue, P orange
    lab = []
    for r in R:
        rr, gg, bb = r[3], r[4], r[5]
        lab.append("D" if (bb > rr * 1.25 and bb > 70) else ("P" if (rr > bb * 1.45 and rr > 70) else "-"))
    seg = []; i = 0
    while i < n:
        j = i
        while j < n and lab[j] == lab[i]: j += 1
        if lab[i] != "-" and T[j - 1] - T[i] >= 0.25:
            if seg and seg[-1][2] == lab[i] and T[i] - seg[-1][1] < 1.5: seg[-1] = (seg[-1][0], T[j - 1], lab[i])
            else: seg.append((T[i], T[j - 1], lab[i]))
        i = j
    darks = []; i = 0
    while i < n:
        base = med(T[i] - 1.5, T[i] - 0.4, 7)
        if base and base > 40 and R[i][7] < 0.80 * base:
            k = i
            while k > 0 and R[k - 1][7] < 0.97 * base: k -= 1
            j = i; low = R[i][7]
            while j < n and R[j][7] < 0.90 * base: low = min(low, R[j][7]); j += 1
            full = k
            while full < j - 1 and R[full][7] > low + 0.08 * base: full += 1
            m = j - 1
            while m > i and R[m][7] > low + 0.08 * base: m -= 1
            if T[j - 1] - T[k] >= 0.12:
                darks.append(dict(on=T[k], full=T[full], up=T[m], off=T[j - 1], base=base, low=low, slot0=med(T[k] - 0.5, T[k] - 0.05, 1), slotlow=min(r[1] for r in R[k:j])))
            i = j + 30
        else: i += 1
    bursts = []; i = 0
    while i < n:
        base = med(T[i] - 1.5, T[i] - 0.4, 6)
        if base is not None and R[i][6] >= base + 700:
            j = i; pk = R[i][6]; tp = T[i]
            while j < n and T[j] < T[i] + 0.6:
                if R[j][6] > pk: pk = R[j][6]; tp = T[j]
                j += 1
            bursts.append((T[i], tp, pk - base)); i = j + 60
        else: i += 1
    lits = []; i = 0
    while i < n:
        base = med(T[i] - 1.2, T[i] - 0.15, 2)
        if base and R[i][2] >= base + 9 and i + 3 < n and R[i + 3][2] >= base + 9:
            j = i
            while j < n and R[j][2] >= base + 6: j += 1
            if not any(d["on"] - 0.1 <= T[i] <= d["off"] + 0.35 for d in darks): lits.append((T[i], T[j - 1] - T[i], max(r[2] for r in R[i:j]) - base))
            i = j + 30
        else: i += 1
    sim = simfade(R)
    return dict(seg=seg, darks=darks, bursts=bursts, lits=lits, sim=sim, dur=T[-1], R=R if verbose else None)

def simfade(R, drop=8.0, flat=3.0):
    """The app's view: slot luma at 60 Hz (every 2nd video frame). Fire when the last 3 scans all fell, the fall from
    the level before is >= drop, and that level was flat (max-min < flat over the 12 scans before the fall).
    Re-arms after 0.8 s. Returns [(t fire, base, slot now)]."""
    S = R[::2]; out = []; last = -9.0
    for i in range(16, len(S)):
        t = S[i][0]
        if t - last < 0.8: continue
        a, b, c, d = S[i - 3][1], S[i - 2][1], S[i - 1][1], S[i][1]
        if not (a > b > c > d): continue
        pre = [q[1] for q in S[i - 15:i - 3]]
        if max(pre) - min(pre) >= flat: continue
        base = sorted(pre)[len(pre) // 2]
        if base - d >= drop and base - a < drop: out.append((t, base, d)); last = t
    return out

def report(p, verbose=False):
    e = events(scan(p), verbose)
    name = os.path.basename(p)[:-4]
    if e is None: print("%s: empty" % name); return None
    print("== %s  %.1f s  banner: %s" % (name, e["dur"], "  ".join("%s %.2f-%.2f" % (k, a, b) for a, b, k in e["seg"]) or "-"))
    def where(t):
        for a, b, k in e["seg"]:
            if a - 0.3 <= t <= b + 0.3: return k
        return "-"
    for d in e["darks"]:
        nb = [b for b in e["bursts"] if d["on"] <= b[0] <= d["off"] + 0.6]
        sm = [s for s in e["sim"] if d["on"] - 0.05 <= s[0] <= d["off"]]
        print("   DARK %s on %.3f  full +%.0f  up +%.0f  off +%.0f ms  scr %.0f->%.0f  slot %.0f->%.0f   burst %s   SIM %s" % (
            where(d["on"]), d["on"], (d["full"] - d["on"]) * 1000, (d["up"] - d["on"]) * 1000, (d["off"] - d["on"]) * 1000, d["base"], d["low"],
            d["slot0"] or 0, d["slotlow"], " ".join("+%.0f ms (+%d)" % ((b[0] - d["on"]) * 1000, b[2]) for b in nb) or "-",
            " ".join("+%.0f ms" % ((s[0] - d["on"]) * 1000) for s in sm) or "MISSED"))
    for b in e["bursts"]:
        if not any(d["on"] <= b[0] <= d["off"] + 0.6 for d in e["darks"]):
            lt = [l for l in e["lits"] if 0 <= b[0] - l[0] <= 1.0]
            print("   BURST %s on %.3f peak %.3f (+%d)%s" % (where(b[0]), b[0], b[1], b[2], "   game pressed %.0f ms before (lit +%.0f)" % ((b[0] - lt[-1][0]) * 1000, lt[-1][2]) if lt else ""))
    for l in e["lits"]:
        if not any(0 <= b[0] - l[0] <= 1.0 for b in e["bursts"]): print("   LIT %s on %.3f for %.0f ms (+%.0f), no burst after" % (where(l[0]), l[0], l[1] * 1000, l[2]))
    fp = [s for s in e["sim"] if not any(d["on"] - 0.05 <= s[0] <= d["off"] for d in e["darks"])]
    if fp: print("   SIM fired outside a dark: " + " ".join("%.2f(%.0f->%.0f)" % s for s in fp))
    return e

if __name__ == "__main__":
    a = [x for x in sys.argv[1:] if x != "-v"]
    for x in a:
        for p in (sorted(glob.glob(os.path.join(x, "*.mp4"))) if os.path.isdir(x) else [x]): report(p, "-v" in sys.argv)
