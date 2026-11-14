"""The rise press + the near gate over a WHOLE recorded match (hand play, no overlay): how many presses land in the
block window, and how many belong to no shot at all? (HANDOFF 0-4z13)

risewhole.py -track <mp4> <mfollow csv> <track.csv> [-h 720]
    finder B (fdtest.find_b = ShotWatcher.findDisc2, with the app's sat2 mask: scoreboard and top-right icons only)
    on the frames whose latest mfollow.py sample shows the block label under the button (btn_label 0.20..0.27 =
    defence; mfollow's own side reads "?" whenever the button is dimmed, which is most of the time before a release),
    at the app's pace (a frame at least 15 ms after the last one taken = about 60 scans/s). track.csv: t_s,x,y,n,ring
    (px of the capture size, 1560 x 720 by default).
risewhole.py -grade <track.csv> <mfollow csv> <key.csv of mrel.py -key> [W 0.12] [D 4.0]
    the rise rule of ShotWatcher.rise2Press (as risenear.py -rule) on that track; near gate = core NearGate on the
    median of my ring (me_x, me_y of the mfollow csv, 1170 x 540) over the last <= 3 samples not older than 0.4 s.
    Locks: 1.5 s after a press, 0.4 s after a trigger that was not near. Graded on the EVENT clock against the
    releases of key.csv: win -305..-220 ms, contest -220..-150, early -600..-305, vearly < -600, late -150..+100
    (touch = event + 40 ms without the pre-arm; HANDOFF 0-4z12). "~" = a release that is not firm. A press within 3 s
    before a shot whose release is unknown is counted apart ("unk"), the rest belongs to no shot.
Timing only, not a game verdict; a release is ANY opponent's shot; the recordings were played by hand, so my player
stood where the user put him.
"""
import sys, os, csv, bisect
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


def follow(p):
    T = []; SD = []; ME = []
    for r in csv.DictReader(open(p, encoding="utf-8")):
        # Defence = the block label under the button (btn_label 0.20..0.27, as mpick.py): mfollow's own side says "?"
        # whenever the button is dimmed, which it is before most releases of recording F.
        T.append(float(r["t_s"])); SD.append("DEF" if 0.20 <= float(r["btn_label"]) <= 0.27 else r["side"] if r["side"] == "OFF" else "?")
        ME.append((float(r["me_x"]), float(r["me_y"])))
    return T, SD, ME


def track(mp4, fol, outp, h):
    import cv2, fdtest as fd
    fd.setup(h); T, SD, _ = follow(fol); v = cv2.VideoCapture(mp4); last = -1.0; n = 0; nd = 0
    # The app's findDisc2 grid (sat2, vc1096) masks the scoreboard and the top-right icons only, NOT the joystick and
    # the action buttons as fdtest.HUD does: a shooter in the left corner has his disc over the joystick.
    gx = np.arange(fd.GW) * fd.S; gy = np.arange(fd.GH) * fd.S
    top = ((gx[None, :] < 0.30 * fd.W) & (gy[:, None] < 0.22 * fd.H)) | ((gx[None, :] > 0.62 * fd.W) & (gy[:, None] < 0.14 * fd.H))
    out = csv.writer(open(outp, "w", newline="")); out.writerow(["t_s", "x", "y", "n", "ring"])
    while v.grab():
        t = v.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
        if t - last < 0.015: continue
        i = bisect.bisect_right(T, t) - 1
        if i < 0 or SD[i] != "DEF" or t - T[i] > 0.5: continue
        ok, im = v.retrieve()
        if not ok: break
        last = t; nd += 1
        im = cv2.resize(im, (fd.W, fd.H), interpolation=cv2.INTER_AREA); m = fd.red(im, False)
        g = m[::fd.S, ::fd.S][:fd.GH, :fd.GW] & ~top & fd.ACTIVE
        sat = np.zeros((fd.GH + 1, fd.GW + 1), np.int32); sat[1:, 1:] = g.cumsum(0).cumsum(1); K = fd.K
        win = sat[K:, K:] - sat[:-K, K:] - sat[K:, :-K] + sat[:-K, :-K]
        b = fd.find_b(win, m, im)
        if b[0]: out.writerow(["%.3f" % t, "%.0f" % b[1], "%.0f" % b[2], b[4], "%.2f" % b[3]]); n += 1
        if nd % 2000 == 0: print("  %.0f s, %d defence frames, %d with the disc" % (t, nd, n), flush=True)
    print("%s: %d defence frames scanned, disc in %d" % (os.path.basename(outp), nd, n))


def grade(trk, fol, keyp, W, D):
    T, SD, ME = follow(fol)
    hdef = sum(min(T[i + 1] - T[i], 0.5) for i in range(len(T) - 1) if SD[i] == "DEF")
    F = [(float(r["t_s"]), float(r["x"]), float(r["y"]), (int(r["n"]) / np.pi) ** 0.5, float(r["ring"])) for r in csv.DictReader(open(trk))]
    t, x, y, rr, ring = np.array(F, np.float64).T
    CW = 1560.0; CH = 720.0

    def near(ts, px, py):
        i = bisect.bisect_right(T, ts) - 1; c = []
        while i >= 0 and ts - T[i] <= 0.4 and len(c) < 3:
            if ME[i][0] >= 0: c.append(ME[i])
            i -= 1
        if not c: return -1
        mx = sorted(q[0] for q in c)[len(c) // 2]; my = sorted(q[1] for q in c)[len(c) // 2]
        return 1 if my / 540.0 - py / CH <= 0.36 and abs(mx / 1170.0 - px / CW) <= 0.14 else 0

    ALL = []; NEAR = []; la = -9.0; ln = -9.0
    for i in range(len(t)):
        if ring[i] < 0.3: continue
        j = int(np.searchsorted(t, t[i] - W))
        if j >= i or abs(t[j] - (t[i] - W)) > 0.02 or t[i] - t[j - 1 if j else 0] > W + 0.06: continue
        q = int(np.searchsorted(t, t[i] - 0.5)); R = float(np.median(rr[q:i + 1]))
        if not 0.7 * R <= rr[j] <= 1.4 * R or not 0.7 * R <= rr[i] <= 1.4 * R: continue
        rise = (y[j] - y[i]) / R
        if not (rise >= D and abs(x[i] - x[j]) / R <= 1.5 * rise): continue
        n = near(t[i], x[i], y[i])
        if t[i] - la >= 1.5: la = float(t[i]); ALL.append((la, n))
        if t[i] - ln >= 1.5:
            if n == 1: ln = float(t[i]); NEAR.append((ln, n))
            else: ln = float(t[i]) - 1.1
    REL = []; UNK = []
    for r in csv.DictReader(open(keyp, encoding="utf-8")):
        if r["release_rel"]: REL.append((float(r["release_s"]), not r["src"].endswith("?"), r["name"] + r["k"]))
        else: UNK.append(float(r["ref_s"]))
    name = os.path.basename(keyp).split(".")[0]
    print("%s: defence %.0f s, disc track %d scans, %d releases (%d firm) + %d shots without a release time | rise W %.2f D %.1f: %d triggers (near 1: %d, 0: %d, no ring: %d)" % (
        name, hdef, len(t), len(REL), sum(1 for q in REL if q[1]), len(UNK), W, D, len(ALL), sum(1 for q in ALL if q[1] == 1), sum(1 for q in ALL if q[1] == 0), sum(1 for q in ALL if q[1] < 0)))
    res = {}
    for lab, P in (("all", [q[0] for q in ALL]), ("near only", [q[0] for q in NEAR])):
        cnt = {}; used = set(); rows = []
        for Rt, firm, nm in REL:
            c = [p for p in P if Rt - 1.0 <= p <= Rt + 0.1]
            if not c: k = "none"; e = None
            else:
                e = min(c, key=lambda p: abs(p - (Rt - 0.26))); used.add(e); d = e - Rt
                k = "win" if -0.305 <= d <= -0.22 else "contest" if -0.22 < d <= -0.15 else "early" if -0.6 <= d < -0.305 else "vearly" if d < -0.6 else "late"
            cnt[k] = cnt.get(k, 0) + 1
            if e is not None: rows.append("%s%s %s %+d" % (nm, "" if firm else "~", k, round((e - Rt) * 1000)))
        rest = [p for p in P if p not in used]; unk = [p for p in rest if any(u - 3.0 <= p <= u + 0.3 for u in UNK)]; stray = [p for p in rest if p not in unk]
        cnt["unk"] = len(unk); cnt["stray"] = len(stray); cnt["presses"] = len(P); res[lab] = cnt
        print("  %-9s presses %2d | %s | unk %d | no shot %d (%.1f per defence minute)" % (lab, len(P), " ".join("%s %d" % (k, cnt.get(k, 0)) for k in ("win", "contest", "early", "vearly", "late", "none")),
              len(unk), len(stray), 60.0 * len(stray) / max(hdef, 1.0)))
        print("     " + ", ".join(rows))
        print("     no shot: " + " ".join("%.1f" % p for p in stray))
    return res


a = sys.argv[1:]
if a[0] == "-track": track(a[1], a[2], a[3], int(a[a.index("-h") + 1]) if "-h" in a else 720)
else: grade(a[1], a[2], a[3], float(a[4]) if len(a) > 4 else 0.12, float(a[5]) if len(a) > 5 else 4.0)
