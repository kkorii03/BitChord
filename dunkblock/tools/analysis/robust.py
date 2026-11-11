"""Size robustness of the silhouette match (HANDOFF 0-4o): the same decider (A, silhouette only) with other ways of
turning the outline into the vector that is compared, each replayed with the shooter drawn larger / smaller.

robust.py <dec.pkl of decide.py -cache> <out folder> [-vars a,b,..] [-scales 0.9,1.0,..]
    writes <out>/<variant>_<scale>.pkl = {video: (stack times, distance to every template (float16), motion (float16))}
    and <out>/<variant>.tpl.pkl (which template is which column); <out>/heights.csv (see below). Evaluate with robeval.py.

Everything is causal (scans at or before the decision scan only), as posematch.stacks(causal=True).
The simulated size change (a warp around the feet) is applied to the SCORED video only; templates stay as recorded.
A simulation is not a recording at another spot: it changes size only, not the viewing angle or what hides the player.

variant = norm / centre / blur / feature / template scales
  norm    none | row     size from the screen row of the feet (posematch -norm; cannot see a simulated size change)
               | height  size from the outline itself: the row above which 10 % of the outline lies (hip-centred crop,
                         rows above the foot ring), median over the last 1.0 s; every outline is resized to HREF
  centre  hips (median x of the lower half over the last 160 ms, as now) | ring (x of the outline in the foot-ring rows)
  blur    Gaussian sigma in cells (1.5 now)
  feature abs (8 outlines) | diff (the 7 changes between outlines 50 ms apart)
  tscales the template rep is also stored drawn at these sizes (multi-scale templates)
motion (stored per scan, used as a gate by robeval.py) = distance between the newest outline and the one 350 ms ago.
"""
import sys, os, csv, pickle
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import truth, posematch

LAGS = posematch.LAGS; HALFW = posematch.HALFW; CELL = posematch.CELL; RING = 75; LEAD = 0.230
MS = [0.88, 0.94, 1.0, 1.06, 1.12]
VARS = {
    "base":     dict(norm="none", cen="hips", blur=1.5, feat="abs", ts=[1.0]),
    "blur25":   dict(norm="none", cen="hips", blur=2.5, feat="abs", ts=[1.0]),
    "blur35":   dict(norm="none", cen="hips", blur=3.5, feat="abs", ts=[1.0]),
    "ms":       dict(norm="none", cen="hips", blur=1.5, feat="abs", ts=MS),
    "ms_b25":   dict(norm="none", cen="hips", blur=2.5, feat="abs", ts=MS),
    "hnorm":    dict(norm="height", cen="hips", blur=1.5, feat="abs", ts=[1.0]),
    "hnorm_b25": dict(norm="height", cen="hips", blur=2.5, feat="abs", ts=[1.0]),
    "row":      dict(norm="row", cen="hips", blur=1.5, feat="abs", ts=[1.0]),
    "ringc":    dict(norm="none", cen="ring", blur=1.5, feat="abs", ts=[1.0]),
    "diff":     dict(norm="none", cen="hips", blur=1.5, feat="diff", ts=[1.0]),
    "diff_b25": dict(norm="none", cen="hips", blur=2.5, feat="diff", ts=[1.0]),
    "hnorm_diff_b25": dict(norm="height", cen="hips", blur=2.5, feat="diff", ts=[1.0]),
    "ms_diff_b25": dict(norm="none", cen="hips", blur=2.5, feat="diff", ts=MS),
    "ms9":      dict(norm="none", cen="hips", blur=1.5, feat="abs", ts=[0.76, 0.82, 0.88, 0.94, 1.0, 1.06, 1.12, 1.18, 1.24]),
    "ms_ringc": dict(norm="none", cen="ring", blur=1.5, feat="abs", ts=MS),
    "row_ms":   dict(norm="row", cen="hips", blur=1.5, feat="abs", ts=MS),        # here a simulated size = how far the real size is off the row law
    "hnorm_ms3": dict(norm="height", cen="hips", blur=1.5, feat="abs", ts=[0.94, 1.0, 1.06]),
    "ms9_mir":  dict(norm="none", cen="hips", blur=1.5, feat="abs", ts=[0.76, 0.82, 0.88, 0.94, 1.0, 1.06, 1.12, 1.18, 1.24], mir=True),   # + every template mirrored
}
SCALES = [0.90, 0.95, 0.97, 1.0, 1.03, 1.05, 1.10]


def resize(x, f):                                           # around the marker x and the foot-ring row
    return cv2.warpAffine(x, np.float32([[f, 0, (1 - f) * x.shape[1] / 2.0], [0, f, (1 - f) * float(RING)]]), (x.shape[1], x.shape[0]))


class Vid:
    def __init__(self, mp4, flip=False):
        z = np.load(mp4[:-4] + ".pose.npz"); self.mp4 = mp4
        self.t = z["t"].astype(np.float64); self.ok = z["ok"]; self.m = z["m"]; self.off = z["off"].astype(np.float64)
        if flip:                                            # the same move seen mirrored (a simulation of the other side of the court)
            self.m = self.m[:, :, ::-1].copy(); self.off = np.where(self.off > -900, -self.off, self.off)
        y = np.array([float(r["y"]) for r in csv.DictReader(open(mp4[:-4] + ".sim.csv"))][:len(self.t)])
        self.ring = (z["gap"][:len(self.t)] + y) * 720.0
        self.vi = np.nonzero(self.ok)[0]; tv = self.tv = self.t[self.vi]; idx = []; picks = []
        for i in self.vi:                                   # the same choice of lagged scans as posematch.stacks
            pick = []
            for lag in LAGS:
                j = np.searchsorted(tv, self.t[i] - lag); best = None
                for q in (j - 1, j):
                    if 0 <= q < len(self.vi) and abs(tv[q] - (self.t[i] - lag)) <= 0.025 and (best is None or abs(tv[q] - (self.t[i] - lag)) < abs(tv[best] - (self.t[i] - lag))): best = q
                if best is None: pick = None; break
                pick.append(self.vi[best])
            if pick is not None: idx.append(i); picks.append(pick)
        self.idx = np.array(idx, int); self.picks = np.array(picks, int).reshape(-1, len(LAGS))
        self.ts = self.t[self.idx] if len(idx) else np.zeros(0)

    def window(self, i, back):                              # ok scans in [t_i - back, t_i]
        tv = self.tv; a = np.searchsorted(tv, self.t[i] - back); b = np.searchsorted(tv, self.t[i], side="right")
        return self.vi[a:b]

    def frames(self, scale, var, href, only=None):
        """-> fr (N x 78*32 float32, rows of scans not needed stay 0), h_run (N, nan where unknown)."""
        N = len(self.t); F = self.m.shape[1] * 2 * HALFW
        need = set(self.vi.tolist()) if only is None else set(int(x) for x in only)
        fr = np.zeros((N, F), np.float32); hrun = np.full(N, np.nan)
        m = self.m; off = self.off; W = m.shape[2]
        if scale != 1.0 and only is None:                   # the whole recording drawn at the simulated size, once
            m = m.copy()
            for i in self.vi: m[i] = resize(self.m[i], scale)
            get = lambda i: m[i]
        else: get = (lambda i: m[i]) if scale == 1.0 else (lambda i: resize(m[i], scale))
        off1 = np.where(off > -900, off * scale, off)

        def centre(i, im, o):
            if var["cen"] == "ring":
                near = self.window(i, 0.16); xs = []
                for q in near:
                    band = (im if q == i else get(q))[RING - 4:RING + 3].sum(0).astype(np.float64)
                    if band.sum() > 0: xs.append((band * np.arange(W)).sum() / band.sum())
                c = int(round(float(np.median(xs)))) if xs else W // 2
            else:
                c = int(round(float(np.median(o[self.window(i, 0.16)])) / CELL)) + W // 2
            return min(W - HALFW, max(HALFW, c))

        fac = np.ones(N)
        if var["norm"] == "row":
            fac = posematch.SIZE_REF / (posematch.SIZE_C + posematch.SIZE_K * self.ring)
        elif var["norm"] == "height":
            h = np.full(N, np.nan)
            hneed = set()
            for i in need: hneed.update(self.window(i, 1.0).tolist())
            for i in hneed:
                im = get(i); c = int(round(float(np.median(off1[self.window(i, 0.16)])) / CELL)) + W // 2; c = min(W - HALFW, max(HALFW, c))
                rm = im[:RING - 3, c - HALFW:c + HALFW].sum(1).astype(np.float64); tot = rm.sum()
                if tot <= 0: continue
                cum = np.cumsum(rm); k = int(np.searchsorted(cum, 0.10 * tot)); prev = cum[k - 1] if k > 0 else 0.0
                h[i] = RING - (k + (0.10 * tot - prev) / max(rm[k], 1e-9))
            for i in need:
                w = h[self.window(i, 1.0)]; w = w[~np.isnan(w)]
                if len(w): hrun[i] = float(np.median(w))
            if href is not None: fac = np.clip(np.where(np.isnan(hrun), 1.0, href / hrun), 0.7, 1.45)
        off2 = np.where(off1 > -900, off1 * fac, off1)
        for i in need:
            im = get(i)
            if abs(fac[i] - 1.0) > 0.01: im = resize(im, float(fac[i]))
            if var["cen"] == "ring": c = centre(i, im, off2)
            else: c = min(W - HALFW, max(HALFW, int(round(float(np.median(off2[self.window(i, 0.16)])) / CELL)) + W // 2))
            fr[i] = cv2.GaussianBlur(im[:, c - HALFW:c + HALFW].astype(np.float32) / 255.0, (0, 0), var["blur"]).reshape(-1)
        return fr, hrun


def feat(S, var, F):                                        # S: n x 8F
    return S[:, :7 * F] - S[:, F:] if var["feat"] == "diff" else S


def dist(A, B):
    aa = (A * A).sum(1)[:, None]; bb = (B * B).sum(1)[None, :]
    return (aa + bb - 2 * A @ B.T) / np.maximum(aa + bb, 1e-6)


if __name__ == "__main__":
    a = sys.argv[1:]; names = list(VARS); scales = SCALES; pos = []; flip = False; tplfrom = None
    i = 0
    while i < len(a):
        if a[i] == "-vars": names = a[i + 1].split(","); i += 1
        elif a[i] == "-scales": scales = [float(x) for x in a[i + 1].split(",")]; i += 1
        elif a[i] == "-flip": flip = True                   # score the recordings mirrored (templates must come from -tplfrom)
        elif a[i] == "-tplfrom": tplfrom = a[i + 1]; i += 1 # frozen templates and HREF of an earlier run: none is made from these recordings
        else: pos.append(a[i])
        i += 1
    B = pickle.load(open(pos[0], "rb")); out = pos[1]; os.makedirs(out, exist_ok=True)
    V = B["V"]; vids = {n: Vid(v["mp4"], flip) for n, v in V.items()}
    assert not flip or tplfrom
    first = {}                                              # template reps: the first rep of every skill, as decide.build
    for n, v in V.items():
        for t, meas, src, kind, k in truth.shots(v["mp4"])["shots"]:
            if k >= 0 and (n, k) not in first: first[(n, k)] = t
    spec = []
    for (n, k), t in first.items():
        z = vids[n]
        if not len(z.ts): continue
        j = int(np.argmin(np.abs(z.ts - (t - LEAD))))
        if abs(z.ts[j] - (t - LEAD)) <= 0.03: spec.append((n, k, j))
    # the common size for norm = height: the median running height over every recording, as recorded
    hp = os.path.join(tplfrom or out, "heights.csv")
    if not os.path.exists(hp):
        with open(hp, "w", newline="") as f:
            w = csv.writer(f); w.writerow(["video", "player", "scans", "ring_px_median", "h_cells_median", "h_p10", "h_p90"])
            for n, z in vids.items():
                fr, hr = z.frames(1.0, dict(VARS["hnorm"]), None, only=z.vi[::6]); g = ~np.isnan(hr)
                if g.sum() < 5: continue
                w.writerow([n, V[n]["player"], int(g.sum()), "%.1f" % np.median(z.ring[g]), "%.2f" % np.median(hr[g]), "%.2f" % np.percentile(hr[g], 10), "%.2f" % np.percentile(hr[g], 90)])
                sys.stderr.write("h"); sys.stderr.flush()
    rows = list(csv.DictReader(open(hp))); wts = np.array([int(r["scans"]) for r in rows]); hs = np.array([float(r["h_cells_median"]) for r in rows])
    href = float(hs[np.argsort(hs)][np.searchsorted(np.cumsum(wts[np.argsort(hs)]), wts.sum() / 2.0)])
    print("HREF %.2f cells (scan-weighted median of the running height over %d recordings)" % (href, len(rows)), flush=True)
    for name in names:
        var = VARS[name]; F = 78 * 2 * HALFW
        tp = os.path.join(tplfrom or out, name + ".tpl.pkl")
        if os.path.exists(tp): TI, TM = pickle.load(open(tp, "rb"))
        else:
            TI = []; TM = []
            for n, k, j in spec:
                for mi, z in enumerate([vids[n], Vid(V[n]["mp4"], True)] if var.get("mir") else [vids[n]]):
                    for s in var["ts"]:
                        fr, _ = z.frames(s, var, href, only=z.picks[j])
                        TM.append(feat(fr[z.picks[j]].reshape(1, -1), var, F)[0]); TI.append((V[n]["player"], n, k, s, mi))
            TM = np.stack(TM).astype(np.float32); pickle.dump((TI, TM), open(tp, "wb"))
        for sc in ([1.0] if var["norm"] == "row" and len(var["ts"]) == 1 else scales):
            op = os.path.join(out, "%s_%.2f.pkl" % (name, sc))
            if os.path.exists(op): continue
            res = {}
            for n, z in vids.items():
                if not len(z.idx): res[n] = (np.zeros(0), np.zeros((0, len(TI)), np.float16), np.zeros(0, np.float16)); continue
                fr, _ = z.frames(sc, var, href)
                D = np.zeros((len(z.idx), len(TI)), np.float16 if len(TI) > 100 else np.float32); mot = np.zeros(len(z.idx), np.float16)
                for c in range(0, len(z.idx), 384):
                    pk = z.picks[c:c + 384]; S = fr[pk].reshape(len(pk), -1)
                    a0 = S[:, :F]; a7 = S[:, 7 * F:]
                    mot[c:c + 384] = ((a0 - a7) ** 2).sum(1) / np.maximum((a0 * a0).sum(1) + (a7 * a7).sum(1), 1e-6)
                    D[c:c + 384] = dist(feat(S, var, F), TM)
                res[n] = (z.ts.copy(), D, mot)
            pickle.dump(res, open(op, "wb"))
            if tplfrom and not os.path.exists(os.path.join(out, name + ".tpl.pkl")): pickle.dump((TI, None), open(os.path.join(out, name + ".tpl.pkl"), "wb"))
            print("%s %.2f done" % (name, sc), flush=True)
