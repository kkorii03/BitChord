"""Detection rate AND accuracy of the my-player tracker (metrack.py), split by how close the two players are.

meacc.py [-k N] [-sheet out.jpg] <folder | mp4> ...      needs <name>.me.csv; decodes every video once (grab only)

Situation of every scan with a shooter, from the distance between my disc and the shooter's feet in disc units
(dx / a, dy / (0.39 a), a = ring half-width; for scans the tracker did not see, the position is interpolated between the
nearest seen scans <= 1 s away, for the evaluation only):
  touching   d < 1: the shooter's feet are inside my ring
  centre     I stand behind the shooter (further from the camera) and less than 0.7 a to the side: his legs and body
             cover the middle of my disc (the usual place in the demonstrations)
  partly     behind and 0.7-1.6 a to the side (his body covers one side), or the two rings overlap (d < 2)
  apart      the rest
  unknown    no seen scan within 1 s
  ("crowded" = several players around: the practice court has one opponent, so there is no such scan)
Detection: share of scans in state seen / pred / lost, and of the app's findMe (old).
Accuracy (independent cue): the orange "SF" tag of my player's name label hangs under my disc. On every Nth seen scan
the tag nearest to the tracked centre (within 2.6 a) is found by colour; the tracker does not use it. Error = how far
(tag - disc) is from the video's median (tag - disc), divided by a. It contains the tag's own error, so it is an upper
bound. "no tag" = the label is hidden (by the shooter) or the tracker is on something else.
Switches: seen -> seen within 60 ms that moves more than 1 a.
"""
import sys, os, csv, glob, bisect, random
import cv2, numpy as np

ASPECT = 0.39
CLS = ["touching", "centre", "partly", "apart", "unknown"]


def tags(im, x0, y0, x1, y1):
    h, w = im.shape[:2]; x0 = max(0, int(x0)); y0 = max(0, int(y0)); x1 = min(w, int(x1)); y1 = min(h, int(y1))
    if x1 - x0 < 10 or y1 - y0 < 10: return []
    c = im[y0:y1, x0:x1].astype(np.int16); b, g, r = c[..., 0], c[..., 1], c[..., 2]
    m = ((r > 190) & (g > 90) & (g < 160) & (b < 70)).astype(np.uint8)
    n, lab, st, cen = cv2.connectedComponentsWithStats(m, 8)
    return [(x0 + cen[i][0], y0 + cen[i][1]) for i in range(1, n) if 150 <= st[i][4] <= 520 and 22 <= st[i][2] <= 40 and 10 <= st[i][3] <= 20]


def load(mp4):
    R = list(csv.DictReader(open(mp4[:-4] + ".me.csv")))
    T = [float(r["t_s"]) for r in R]
    si = [i for i, r in enumerate(R) if r["state"] == "seen"]
    a = float(np.median([float(R[i]["a"]) for i in si])) if si else 46.0
    st = [T[i] for i in si]; cls = []
    for i, r in enumerate(R):
        if r["state"] == "-": cls.append(None); continue
        if r["state"] == "seen": mx, my = float(r["mx"]), float(r["my"])
        else:
            j = bisect.bisect_left(st, T[i]); c = [k for k in (j - 1, j) if 0 <= k < len(si) and abs(st[k] - T[i]) <= 1.0]
            if not c: cls.append("unknown"); continue
            if len(c) == 2 and st[c[1]] > st[c[0]]:
                f = (T[i] - st[c[0]]) / (st[c[1]] - st[c[0]]); p, q = R[si[c[0]]], R[si[c[1]]]
                mx = float(p["mx"]) + f * (float(q["mx"]) - float(p["mx"])); my = float(p["my"]) + f * (float(q["my"]) - float(p["my"]))
            else: p = R[si[c[0]]]; mx, my = float(p["mx"]), float(p["my"])
        dx = abs(mx - float(r["sx"])) / a; dy = (float(r["sy"]) - my) / (ASPECT * a); d = np.hypot(dx, dy)
        cls.append("touching" if d < 1 else "centre" if dy > 0 and dx < 0.7 else "partly" if (dy > 0 and dx < 1.6) or d < 2 else "apart")
    return R, T, a, cls


def run(mp4, K, tiles):
    R, T, a, cls = load(mp4); name = os.path.basename(mp4)[:-4]
    det = {c: dict(n=0, seen=0, pred=0, lost=0, old=0) for c in CLS}
    for r, c in zip(R, cls):
        if c is None: continue
        det[c]["n"] += 1; det[c][r["state"]] += 1; det[c]["old"] += float(r["old_x"]) >= 0
    sw = 0; prev = None
    for i, r in enumerate(R):
        if r["state"] != "seen": continue
        if prev is not None and T[i] - T[prev] <= 0.06 and np.hypot(float(r["mx"]) - float(R[prev]["mx"]), (float(r["my"]) - float(R[prev]["my"])) / ASPECT) > a: sw += 1
        prev = i
    want = {}; k = 0
    for i, r in enumerate(R):
        if r["state"] == "seen":
            if k % K == 0: want[i] = None
            k += 1
    v = cv2.VideoCapture(mp4); n = 0; i = -1; last = max(want) if want else -1; obs = []
    while i < last:
        if not v.grab(): break
        n += 1
        if n % 2 != 0: continue
        i += 1
        if i not in want: continue
        ok, im = v.retrieve()
        if not ok: continue
        if im.shape[0] != 720: im = cv2.resize(im, (int(im.shape[1] * 720 / im.shape[0]), 720))
        mx, my = float(R[i]["mx"]), float(R[i]["my"])
        tg = [t for t in tags(im, mx - 3.2 * a, my + 5, mx + 2.6 * a, my + 2.6 * a) if np.hypot(t[0] - mx, t[1] - my) <= 2.6 * a]
        t = min(tg, key=lambda t: np.hypot(t[0] - (mx - 0.6 * a), t[1] - (my + a))) if tg else None
        obs.append((i, cls[i], mx, my, t))
        if tiles is not None and random.random() < 0.06:
            x0 = int(min(max(0, mx - 150), im.shape[1] - 300)); y0 = int(min(max(0, my - 110), 720 - 220)); c = im[y0:y0 + 220, x0:x0 + 300].copy()
            aa = int(float(R[i]["a"])); cv2.ellipse(c, (int(mx - x0), int(my - y0)), (aa, int(aa * ASPECT)), 0, 0, 360, (0, 255, 0), 1)
            cv2.drawMarker(c, (int(mx - x0), int(my - y0)), (0, 255, 0), cv2.MARKER_CROSS, 8, 1)
            cv2.circle(c, (int(float(R[i]["sx"]) - x0), int(float(R[i]["sy"]) - y0)), 4, (0, 0, 255), 1)
            if t: cv2.rectangle(c, (int(t[0] - x0) - 17, int(t[1] - y0) - 8), (int(t[0] - x0) + 17, int(t[1] - y0) + 8), (255, 255, 0), 1)
            cv2.putText(c, "%s %.1f %s" % (name, T[i], cls[i]), (3, 12), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 255, 255), 1)
            tiles.setdefault(cls[i], []).append(c)
    off = [(o[4][0] - o[2], o[4][1] - o[3]) for o in obs if o[4]]
    err = []
    if len(off) >= 5:
        mdx = float(np.median([o[0] for o in off])); mdy = float(np.median([o[1] for o in off]))
        for o in obs:
            if o[4]: err.append((o[1], np.hypot(o[4][0] - o[2] - mdx, o[4][1] - o[3] - mdy) / a))
    notag = {c: sum(1 for o in obs if o[1] == c and not o[4]) for c in CLS}; samp = {c: sum(1 for o in obs if o[1] == c) for c in CLS}
    e = np.array([x[1] for x in err]) if err else np.zeros(0)
    print("%-18s a %2.0f | seen scans %4d switches %d | sampled %3d tag %3d | error/a median %.2f p90 %.2f >0.5a %d" % (
        name, a, sum(d["seen"] for d in det.values()), sw, len(obs), len(off), np.median(e) if len(e) else -1, np.percentile(e, 90) if len(e) else -1, int((e > 0.5).sum())))
    sys.stdout.flush()
    return det, err, notag, samp, sw


if __name__ == "__main__":
    a = sys.argv[1:]; K = 6; sheet = None; paths = []
    i = 0
    while i < len(a):
        if a[i] == "-k": K = int(a[i + 1]); i += 1
        elif a[i] == "-sheet": sheet = a[i + 1]; i += 1
        else: paths.append(a[i])
        i += 1
    random.seed(7); tiles = {} if sheet else None
    D = {c: dict(n=0, seen=0, pred=0, lost=0, old=0) for c in CLS}; E = {c: [] for c in CLS}; NT = dict.fromkeys(CLS, 0); SM = dict.fromkeys(CLS, 0); SW = 0; nv = 0
    for x in paths:
        for p in (sorted(glob.glob(os.path.join(x, "*.mp4"))) if os.path.isdir(x) else [x]):
            if not os.path.exists(p[:-4] + ".me.csv"): continue
            det, err, notag, samp, sw = run(p, K, tiles); nv += 1; SW += sw
            for c in CLS:
                for k in D[c]: D[c][k] += det[c][k]
                NT[c] += notag[c]; SM[c] += samp[c]
            for c, e in err: E[c].append(e)
    print("\nvideos %d, switches (seen->seen > 1 a within 60 ms) %d" % (nv, SW))
    print("%-9s %7s | %5s %5s %5s | %8s | %7s %6s %6s | %10s %6s %6s %7s" % ("situation", "scans", "seen", "pred", "lost", "old find", "sampled", "tag", "no tag", "err median", "p90", "max", ">0.5a"))
    for c in CLS + ["all"]:
        d = D[c] if c != "all" else {k: sum(D[q][k] for q in CLS) for k in D["apart"]}
        e = np.array(E[c] if c != "all" else sum(E.values(), [])); sm = SM[c] if c != "all" else sum(SM.values()); nt = NT[c] if c != "all" else sum(NT.values())
        n = max(1, d["n"])
        print("%-9s %7d | %4.0f%% %4.0f%% %4.0f%% | %7.0f%% | %7d %6d %6d | %10s %6s %6s %7s" % (c, d["n"], 100 * d["seen"] / n, 100 * d["pred"] / n, 100 * d["lost"] / n, 100 * d["old"] / n,
              sm, len(e), nt, "%.2f" % np.median(e) if len(e) else "-", "%.2f" % np.percentile(e, 90) if len(e) else "-", "%.2f" % e.max() if len(e) else "-", "%d (%.0f%%)" % ((e > 0.5).sum(), 100 * (e > 0.5).mean()) if len(e) else "-"))
    if sheet:
        rows = []
        for c in ("touching", "centre", "partly", "apart"):
            t = tiles.get(c, []); random.shuffle(t); t = t[:6]
            while len(t) < 6: t.append(np.zeros((220, 300, 3), np.uint8))
            rows.append(np.hstack(t))
        cv2.imwrite(sheet, np.vstack(rows), [cv2.IMWRITE_JPEG_QUALITY, 85])
