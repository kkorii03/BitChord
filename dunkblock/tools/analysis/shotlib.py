"""Shot library prototype: learn each shot animation from the game's demonstrations, press by recognising it.

shotlib.py [-thr D] [-lead ms] [-v] <folder | mp4> ...      (needs <name>.sim.csv / .simev.csv from shotsim.py)

Measured on the demonstrations of 11-11 (HANDOFF 0-4j): the game's own press is 208-283 ms (median ~230) before the
ball leaves the hand ("release step" of the slot left-up of the block button), for every shot type, while the app's
gap rules press anywhere from 650 ms before to 90 ms after the release depending on the animation. An animation is
deterministic (two demonstrations of a skill differ by < 0.01 H in gap), so the time left until the release can be
read off the pose track itself.
  template = one release of a skill: gap (foot ring y - marker y) and top_dy (marker to the top of the red outline) on a
             60 Hz grid from 750 ms before the press time P = release - lead to P.
  live     = the last 400 ms of the same two tracks. For every template and every phase j (0..20 scans before P) the
             distance is: (mean |gap_live - a * gap_tpl| + 0.5 * mean |top_live - top_tpl|) / excursion of the template
             window, a = least-squares scale in 0.8..1.25 (the same animation looks bigger near the camera). A phase
             whose template window is flat (gap range < 0.03 H, or excursion < 0.05) is not used.
  press    = best distance < thr at phase 0, 1.5 s lockout.
Evaluation here: template = FIRST demonstration of each skill; test = its other releases (second demonstration and the
practice reps) and, for false presses, all the other time of all videos.

RESULT 11-11 (HANDOFF 0-4j): NOT usable as a trigger. 45 of 53 held-out reps with a usable template match below 0.10
within +-40 ms, but the same threshold also matches 70 moments that are not the press time (dribble and approach
moves, many 0.5-1 s before the real shot), and 31 of 84 reps have a flat template. Two numbers are too little input.
"""
import sys, os, csv, glob, bisect, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vidskill

STEP = 1.0 / 60; TPL_BACK = 0.75; WIN = 0.40; NPH = 21; MIN_PAIRS = 12; FLAT = 0.03; TOPW = 0.5; INFO = 0.05


def track(mp4):
    """[(t, gap or -1, top or -1)] per scan, gap as the app computes it (ring held 150 ms, valid 0.12..0.6)."""
    out = []; last = (-9.0, -1.0)
    for r in csv.DictReader(open(mp4[:-4] + ".sim.csv")):
        t = float(r["t_s"]); ry = float(r["ring_y"]); y = float(r["y"]); td = float(r["top_dy"])
        if r["disc"] != "1": out.append((t, -1.0, -1.0)); continue
        if ry >= 0: last = (t, ry)
        g = last[1] - y if t - last[0] < 0.15 else -1.0
        out.append((t, g if 0.12 <= g <= 0.6 else -1.0, min(td, 0.06) if td >= 0 else -1.0))
    return out


def releases(mp4):
    ev = [(float(r["t_s"]), r["event"]) for r in csv.DictReader(open(mp4[:-4] + ".simev.csv", encoding="utf-8"))]
    e = vidskill.events(vidskill.scan(mp4))
    segs = e["seg"] if e else []
    rel = []
    ds = [s for s in segs if s[2] == "D"]
    for t, n in ev:
        if n != "sweep_rel" or not ds or t < ds[0][0]: continue      # before the first demonstration = the previous skill
        k = max(i for i, s in enumerate(ds) if s[0] <= t)
        kind = "D" if ds[k][0] - 0.3 <= t <= ds[k][1] + 0.3 else "P"
        rel.append((t, k, kind))
    return rel


def interp(T, V, t):
    """Value of a track column at time t: linear between the two neighbours when both are valid and < 60 ms apart."""
    i = bisect.bisect_left(T, t)
    if i <= 0 or i >= len(T): return -1.0
    a, b = V[i - 1], V[i]
    if a < 0 or b < 0 or T[i] - T[i - 1] > 0.06: return -1.0
    f = (t - T[i - 1]) / (T[i] - T[i - 1])
    return a + f * (b - a)


class Template:
    def __init__(self, name, trk, p):
        self.name = name
        T = [r[0] for r in trk]; G = [r[1] for r in trk]; U = [r[2] for r in trk]
        n = int(round(TPL_BACK / STEP))
        self.g = [interp(T, G, p - (n - i) * STEP) for i in range(n + 1)]      # index n = P
        self.u = [interp(T, U, p - (n - i) * STEP) for i in range(n + 1)]
        self.n = n
        w = int(round(WIN / STEP))
        self.ok = []                                                           # phase usable: its window is not flat
        for j in range(NPH):
            seg = [v for v in self.g[max(0, n - j - w):n - j + 1] if v >= 0]
            self.ok.append(len(seg) >= MIN_PAIRS and max(seg) - min(seg) >= FLAT)

    def dist(self, live, j):
        """live = [(age s, gap, top)] newest first. Distance to this template at phase j scans before P."""
        if not self.ok[j]: return 9.0
        sg = sgt = stt = 0.0; pairs = []
        for age, g, u in live:
            x = self.n - j - age / STEP
            i = int(math.floor(x))
            if i < 0 or i + 1 > self.n: continue
            f = x - i
            tg0, tg1 = self.g[i], self.g[min(self.n, i + 1)]
            if g < 0 or tg0 < 0 or tg1 < 0: continue
            tg = tg0 + f * (tg1 - tg0)
            tu0, tu1 = self.u[i], self.u[min(self.n, i + 1)]
            tu = tu0 + f * (tu1 - tu0) if tu0 >= 0 and tu1 >= 0 else -1.0
            pairs.append((g, tg, u, tu)); sgt += g * tg; stt += tg * tg
        if len(pairs) < MIN_PAIRS: return 9.0
        a = min(1.25, max(0.8, sgt / stt))
        dg = sum(abs(g - a * tg) for g, tg, u, tu in pairs) / len(pairs)
        tgs = [tg for g, tg, u, tu in pairs]
        info = a * (max(tgs) - min(tgs))
        tp = [(u, tu) for g, tg, u, tu in pairs if u >= 0 and tu >= 0]
        du = 0.0
        if len(tp) >= MIN_PAIRS:
            du = sum(abs(u - tu) for u, tu in tp) / len(tp)
            info += TOPW * (max(tu for u, tu in tp) - min(tu for u, tu in tp))
        if info < INFO: return 9.0
        return (dg + TOPW * du) / info


def run(trk, lib, thr, skip=None):
    """Presses of the library on one track: [(t, template name, distance)]. skip = template names not to use."""
    out = []; lock = -9.0; prev0 = {}
    use = [tp for tp in lib if not skip or tp.name not in skip]
    for i, (t, g, u) in enumerate(trk):
        if t - lock < 1.5: continue
        live = []; k = i
        while k >= 0 and t - trk[k][0] <= WIN: live.append((t - trk[k][0], trk[k][1], trk[k][2])); k -= 1
        if sum(1 for a, gg, uu in live if gg >= 0) < MIN_PAIRS: prev0.clear(); continue
        best = (9.0, None)
        for tp in use:
            d0 = tp.dist(live, 0)
            if d0 < best[0]: best = (d0, tp)
        if best[0] < thr: out.append((t, best[1].name, best[0])); lock = t
    return out


def main():
    a = sys.argv[1:]; thr = 0.10; lead = 0.230; verbose = False; paths = []
    i = 0
    while i < len(a):
        if a[i] == "-thr": thr = float(a[i + 1]); i += 1
        elif a[i] == "-lead": lead = float(a[i + 1]) / 1000; i += 1
        elif a[i] == "-v": verbose = True
        else: paths.append(a[i])
        i += 1
    vids = []
    for x in paths:
        vids += sorted(glob.glob(os.path.join(x, "*.mp4"))) if os.path.isdir(x) else [x]
    vids = [v for v in vids if os.path.exists(v[:-4] + ".simev.csv")]
    data = {}; lib = []
    for v in vids:
        name = os.path.basename(v)[:-4]
        trk = track(v); rel = releases(v)
        data[name] = (trk, rel)
        seen = set()
        for t, k, kind in rel:
            if k in seen: continue
            seen.add(k)
            tp = Template("%s#%d" % (name, k), trk, t - lead)
            lib.append(tp)
    flat = [tp.name for tp in lib if not tp.ok[0]]
    print("templates %d (first release of each skill), unusable at the press phase (flat window): %d  %s" % (len(lib), len(flat), " ".join(flat)))
    hit = late = early = miss = 0; false = 0; errs = []; total_s = 0.0
    for name, (trk, rel) in data.items():
        pr = run(trk, lib, thr)
        total_s += trk[-1][0] - trk[0][0] if trk else 0
        used = set(); seen = set(); line = []
        for t, k, kind in rel:
            first = k not in seen; seen.add(k)
            p = t - lead
            c = [(x, n, d) for x, n, d in pr if abs(x - p) <= 0.3]
            tag = "tpl" if first else kind
            if not c:
                line.append("%s:none" % tag)
                if not first: miss += 1
                continue
            x, n, d = min(c, key=lambda q: abs(q[0] - p)); used.add(x)
            e = round((x - p) * 1000)
            line.append("%s:%+d%s" % (tag, e, "" if n.startswith(name + "#") else "(%s)" % n))
            if first: continue
            errs.append(e)
            if abs(e) <= 60: hit += 1
            elif e > 0: late += 1
            else: early += 1
        fp = [(x, n, d) for x, n, d in pr if x not in used]
        false += len(fp)
        if verbose or True:
            print("%-18s %s%s" % (name, "  ".join(line) or "(no release)", "   FALSE " + " ".join("%.2f(%s %.4f)" % q for q in fp) if fp else ""))
    n = hit + late + early + miss
    errs.sort()
    print("thr %.4f lead %d ms | held-out releases %d: within 60 ms %d, early %d, late %d, no press %d | median error %s ms | false presses %d in %.0f s of video" % (
        thr, lead * 1000, n, hit, early, late, miss, errs[len(errs) // 2] if errs else "-", false, total_s))


if __name__ == "__main__":
    main()
