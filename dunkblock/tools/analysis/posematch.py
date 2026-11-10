"""Go / no-go test of the image-based shot recogniser (HANDOFF 0-6 "0-아침"): does the shooter's silhouette over the
last 350 ms tell the press moment (release - 230 ms) apart from dribbles and approach moves?

posematch.py [-lead ms] [-own] [-thr D] <folder | mp4> ...      needs .pose.npz (poseclip.py), .simev.csv (shotsim.py)

  stack    = 8 silhouettes (now, -50, ... -350 ms), each 78 x 32 cells centred on the hips (running median of `off`),
             blurred by 1.5 cells so that a one-cell shift still overlaps.
  distance = |a - b|^2 / (|a|^2 + |b|^2)   (0 = same, 1 = nothing in common)
  template = the stack at P = release - lead of the FIRST release of each skill (first demonstration).
  test     = (1) the other releases of the same skill (second demonstration, practice reps): lowest distance to the own
             template within +-250 ms of their P and where it is; (2) everywhere further than 300 ms from every P: the
             lowest distance to ANY template (-own: only templates of the same player) = what would be a false press.
"""
import sys, os, glob
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shotlib

LAGS = [0.05 * k for k in range(8)]; HALFW = 16; CELL = 4


def stacks(mp4):
    z = np.load(mp4[:-4] + ".pose.npz"); t = z["t"].astype(np.float64); ok = z["ok"]; m = z["m"]; off = z["off"]
    N = len(t); vi = np.nonzero(ok)[0]
    cen = np.zeros(N, np.float32); fr = np.zeros((N, m.shape[1], 2 * HALFW), np.float32)
    for i in vi:
        near = vi[(t[vi] >= t[i] - 0.08) & (t[vi] <= t[i] + 0.08)]
        c = int(round(float(np.median(off[near])) / CELL)) + m.shape[2] // 2
        c = min(m.shape[2] - HALFW, max(HALFW, c))
        fr[i] = cv2.GaussianBlur(m[i, :, c - HALFW:c + HALFW].astype(np.float32) / 255.0, (0, 0), 1.5)
    tv = t[vi]; S = []; idx = []
    for i in vi:
        pick = []
        for lag in LAGS:
            j = np.searchsorted(tv, t[i] - lag)
            best = None
            for q in (j - 1, j):
                if 0 <= q < len(vi) and abs(tv[q] - (t[i] - lag)) <= 0.025 and (best is None or abs(tv[q] - (t[i] - lag)) < abs(tv[best] - (t[i] - lag))): best = q
            if best is None: pick = None; break
            pick.append(vi[best])
        if pick is None: continue
        S.append(fr[pick].reshape(-1)); idx.append(i)
    if not S: return np.zeros(0), np.zeros((0, 1), np.float32)
    return t[idx], np.stack(S)


def dist(A, B):
    """A: n x D, B: k x D -> n x k."""
    aa = (A * A).sum(1)[:, None]; bb = (B * B).sum(1)[None, :]
    return (aa + bb - 2 * A @ B.T) / np.maximum(aa + bb, 1e-6)


def main():
    a = sys.argv[1:]; lead = 0.230; own = False; thr = None; paths = []
    i = 0
    while i < len(a):
        if a[i] == "-lead": lead = float(a[i + 1]) / 1000; i += 1
        elif a[i] == "-thr": thr = float(a[i + 1]); i += 1
        elif a[i] == "-own": own = True
        else: paths.append(a[i])
        i += 1
    vids = []
    for x in paths:
        vids += sorted(glob.glob(os.path.join(x, "*.mp4"))) if os.path.isdir(x) else [x]
    vids = [v for v in vids if os.path.exists(v[:-4] + ".pose.npz")]
    data = {}; names = []; T = []
    for v in vids:
        name = os.path.basename(v)[:-4]
        ts, S = stacks(v); rel = shotlib.releases(v)
        data[name] = (ts, S, rel)
        seen = set()
        for t, k, kind in rel:
            if k in seen: continue
            seen.add(k)
            if not len(ts): continue
            j = int(np.argmin(np.abs(ts - (t - lead))))
            if abs(ts[j] - (t - lead)) > 0.03: print("no template for %s#%d (no silhouette at the press time)" % (name, k)); continue
            names.append("%s#%d" % (name, k)); T.append(S[j])
    T = np.stack(T); player = [n.split("_")[0] for n in names]
    print("templates %d from %d videos, lead %d ms%s" % (len(T), len(vids), lead * 1000, ", own player only" if own else ""))
    held = []; false = []; secs = 0.0
    for name, (ts, S, rel) in data.items():
        if not len(ts): continue
        secs += ts[-1] - ts[0]
        use = [j for j in range(len(names)) if not own or player[j] == name.split("_")[0]]
        D = dist(S, T[use]); best = D.min(1); who = D.argmin(1)
        seen = set(); line = []
        for t, k, kind in rel:
            first = k not in seen; seen.add(k)
            if first: continue
            me = "%s#%d" % (name, k)
            if me not in names: line.append("%s:notpl" % kind); held.append((9.0, 0, me, kind)); continue
            col = use.index(names.index(me)); p = t - lead
            w = np.nonzero(np.abs(ts - p) <= 0.25)[0]
            if not len(w): line.append("%s:nosil" % kind); held.append((9.0, 0, me, kind)); continue
            j = w[int(np.argmin(D[w, col]))]
            held.append((float(D[j, col]), round((ts[j] - p) * 1000), me, kind))
            line.append("%s:%.3f@%+d" % (kind, D[j, col], round((ts[j] - p) * 1000)))
        P = [t - lead for t, k, kind in rel]
        far = np.array([all(abs(x - p) > 0.3 for p in P) for x in ts])
        cand = sorted((float(best[j]), float(ts[j]), names[use[who[j]]]) for j in np.nonzero(far)[0])
        picks = []
        for d, x, n in cand:
            if all(abs(x - q[1]) > 0.5 for q in picks): picks.append((d, x, n))
            if len(picks) >= 6: break
        for d, x, n in picks:
            nxt = [r[0] - x for r in rel if 0 < r[0] - x < 1.3]
            false.append((d, name, x, n, min(nxt) if nxt else None))
        print("%-18s %s | elsewhere: %s" % (name, "  ".join(line), "  ".join("%.3f@%.2f(%s)" % q for q in picks[:4])))
    hv = sorted(h[0] for h in held)
    print("\nheld-out reps %d: own-template distance  %s" % (len(hv), " ".join("%.3f" % x for x in hv)))
    okh = [h for h in held if h[0] < 9]
    print("held-out timing error (ms) of the best match: %s" % " ".join("%+d" % h[1] for h in sorted(okh, key=lambda h: h[1])))
    false.sort()
    print("lowest elsewhere (distance, then s until the next release if < 1.3 s): %s" % " ".join("%.3f%s" % (f[0], "" if f[4] is None else "/%.1f" % f[4]) for f in false[:80]))
    print("\nthr    held-out hit(+-60ms)  miss   false presses (elsewhere, 0.5 s apart)  per minute")
    for th in ([thr] if thr else [0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5]):
        hit = sum(1 for h in held if h[0] < th and abs(h[1]) <= 60)
        fp = sum(1 for f in false if f[0] < th)
        print("%.2f   %3d / %3d            %3d    %3d      %.1f" % (th, hit, len(held), len(held) - hit, fp, fp * 60 / max(secs, 1)))
    if thr:
        for f in false:
            if f[0] < thr: print("  FALSE %.3f %-18s @%.2f tpl=%s%s" % (f[0], f[1], f[2], f[3], "" if f[4] is None else "  (release %.2f s later)" % f[4]))


if __name__ == "__main__":
    main()
