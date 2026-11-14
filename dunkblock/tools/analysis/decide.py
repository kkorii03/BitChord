"""Offline decider for the regular-shot block, replayed on recordings. Evaluation only: nothing here is in the app.

decide.py [-thr D] [-cache f.pkl] [-causal] [-save tpl.npz | -frozen tpl.npz] [-log presses.csv] [-dump shots.txt] <folder | mp4> ...
    needs <name>.sim.csv .simev.csv (shotsim.py), .pose.npz (poseclip.py); .me.csv (metrack.py) for the position log

Candidates (what the detectors say, each at the scan where it becomes known):
  S  silhouette: the shooter's outline over the last 350 ms (posematch.stacks, causal centring) against templates; a
     candidate when the lowest distance first drops below thr (re-armed above thr + 0.03).
     Template = the stack at release - 230 ms of the FIRST rep of a skill. Which templates a video may use:
       own   the same player's (as if the user picked the opponent)        loso  the same player's other skills only
       lopo  other players' only (= a player we have no templates for)     all   every player's
  G  the app's gap rules replayed from <name>.sim.csv (shotsim.Sim.onRow). "X" = the app as it is (press lock 1.5 s,
     reject lock 0.4 s); "Gc" = the same rules with a 0.25 s press lock and no reject lock = a candidate stream.
Deciders (class Decider: ONE owner of the button, called once per scan in time order with that scan's values only;
after a press nothing is pressed for J seconds = "my player is in the air"; J is NOT measured in the game):
  X   the app's rules as they are                       (baseline, existing APK)
  A   silhouette only
  U   silhouette or gap, whichever comes first          (no priority; shown as the thing to avoid)
  B   silhouette first, gap held: a gap candidate only opens a hold of H ms and presses when the hold runs out; a
      silhouette candidate inside the hold presses instead and cancels it
  Bw  B, and the hold is kept open (up to HMAX) while the silhouette distance is below WATCH = "a known move is under
      way, wait for it"
  no-template experiments: the same deciders with loso / lopo templates; "gap assists only where there is no template"
      = A where the player has templates, the gap rules where not (lopo rows: X or G alone)
Scoring, on held-out shots only (not the template rep; with -frozen: every shot, no template is made from the videos):
  TIMING (a yardstick, NOT a game verdict): press - release in ok -330..-150 ms (the game's own press in demonstrations
     is -208..-283), early -600..-330, very early -1000..-600, late -150..+100, miss. extra = presses further than that
     from every shot, outside dark / unknown zones, the first 1.3 s and last 0.5 s.
  sweep = presses in -280..-105 ms: the only window measured WITH game verdicts so far (Nowitzki winning shot, standing
     where the demonstration stands: 22 blocks / 25 presses inside, 1 / 4 at -305, 0 outside; HANDOFF 0-4i). One skill.
  No decider here has pressed in the game: its real block rate is unmeasured.
-causal: truncation test. Every stage is run again on the recording cut at a random time; everything before the cut
  must be identical to the full run (silhouette stacks bit for bit, rule events, decider presses; for the first 3
  videos also the frame stage: shotsim rows and poseclip arrays). Positive control: the centred (non-causal) stacks
  must FAIL the same test.
-save / -frozen: freeze the templates (and thr) to a file / evaluate other recordings with them, making none.
-log: every press of A and B with where my player stood (metrack "seen" scans at or before the press only; recorded,
  never used for the decision).
"""
import sys, os, csv, glob, math, pickle, random, bisect
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shotsim, truth, posematch

LEAD = 0.230
LEADS = [0.40, 0.30, 0.25, 0.23, 0.20, 0.15]
SWEEPW = (-0.280, -0.105)
GKEYS = (("X", 1.5, 0.4), ("X1", 1.5, 0.0), ("Gc", 0.25, 0.0))
CL = ["ok", "early", "vearly", "late", "miss"]
ASPECT = 0.39


class Replay(shotsim.Sim):
    """The app's rules fed with sim.csv rows. findMe has no picture here: 'too far' never rejects (position is only
    recorded at this stage, see -log)."""
    def __init__(self, lock, rej):
        super().__init__()
        self.LOCK_NS = int(lock * 1e9); self.REJECT_LOCK_NS = int(rej * 1e9)

    def findMe(self, sx, sy):
        self.meX = -1.0; self.meY = -1.0
        return -1.0


def gap(rows, lock, rej):
    s = Replay(lock, rej)
    for r in rows: s.onRow(r)
    ev = sorted((round(e[0], 4), e[1]) for e in s.events)
    return [t for t, n in ev if n == "pressed"], ev


class Decider:
    """One owner of the block button. step(t, d, g): t = this scan, d = lowest template distance at this scan (None =
    no silhouette or no template), g = a gap candidate became known at this scan. Returns "S" / "G" when it presses."""
    def __init__(self, mode, thr=0.15, H=0.30, J=1.5, delay=0.0, watch=0.0, hmax=0.0):
        self.mode = mode; self.thr = thr; self.H = H; self.J = J; self.delay = delay; self.watch = watch; self.hmax = hmax
        self.armed = True; self.sdue = None; self.hold = None; self.hold0 = 0.0; self.last = -9.0; self.swallowed = 0

    def step(self, t, d, g):
        s = False
        if self.mode != "G" and d is not None:
            if self.armed and d < self.thr:
                self.armed = False
                if self.delay > 0: self.sdue = t + self.delay      # template taken earlier than release - 230 ms: wait the difference
                else: s = True
            elif d > self.thr + 0.03: self.armed = True
        if self.sdue is not None and t >= self.sdue: s = True; self.sdue = None
        fire = None
        if self.mode == "S": fire = "S" if s else None
        elif self.mode == "G": fire = "G" if g else None
        elif self.mode == "U": fire = "S" if s else "G" if g else None
        else:                                                      # "B"
            if s or self.sdue is not None:
                self.hold = None
                if s: fire = "S"
            else:
                if g and self.hold is None:
                    if t - self.last >= self.J: self.hold = t + self.H; self.hold0 = t
                    else: self.swallowed += 1
                if self.hold is not None and self.watch > 0 and d is not None and d < self.watch and t - self.hold0 < self.hmax:
                    self.hold = max(self.hold, t + self.H)
                if self.hold is not None and t >= self.hold: fire = "G"; self.hold = None
        if fire is None: return None
        if t - self.last < self.J: self.swallowed += 1; return None
        self.last = t
        return fire


def run(v, mode, cols=None, gkey="Gc", **kw):
    """-> presses [(t, "S" | "G")], swallowed."""
    dec = Decider(mode, **kw)
    dm = v["D"][:, cols].min(1) if cols is not None and len(cols) and len(v["sil_k"]) else None
    G = v["G"][gkey]; ST = v["sil_k"]; gi = 0; si = 0; out = []
    for t in v["scan_k"]:
        while si < len(ST) and ST[si] < t - 1e-5: si += 1
        d = float(dm[si]) if dm is not None and si < len(ST) and abs(ST[si] - t) <= 1e-5 else None
        g = False
        while gi < len(G) and G[gi] <= t + 1e-5: g = True; gt = G[gi]; gi += 1
        w = dec.step(t, d, g)
        if w: out.append((gt if w == "G" and mode != "B" else t, w))      # an unheld gap press keeps the rules' own time (the app schedules it, not the scan)
    return out, dec.swallowed


def klass(e):
    return "ok" if -0.33 <= e <= -0.15 else "early" if -0.6 <= e < -0.33 else "vearly" if e < -0.6 else "late"


def union_len(iv):
    n = 0.0; end = -1e9
    for a, b in sorted(iv):
        if b <= end: continue
        n += b - max(a, end); end = b
    return n


def score(V, P, sel=None):
    c = dict.fromkeys(CL, 0); extra = 0; secs = 0.0; sw = 0; det = []; src = {"S": 0, "G": 0}
    for name, v in V.items():
        if sel and not sel(v): continue
        pr = P[name]
        for t in v["held"]:
            d = [(x - t, w) for x, w in pr if -1.0 <= x - t <= 0.1]
            e, w = min(d, key=lambda q: abs(q[0] + LEAD)) if d else (None, None)
            k = klass(e) if d else "miss"; c[k] += 1
            if k == "ok": src[w] += 1
            if e is not None and SWEEPW[0] <= e <= SWEEPW[1]: sw += 1
            det.append((name, t, v["kind"][t], e, k, w))
        for x, w in pr:
            if any(-1.0 <= x - t <= 0.1 for t in v["shots"]) or any(a <= x <= b for a, b in v["excl"]): continue
            extra += 1
        secs += v["dur"] - union_len(v["excl"])
    return dict(c=c, extra=extra, mins=secs / 60.0, sw=sw, det=det, n=sum(c.values()), src=src)


HEAD = "%-46s | %4s %5s %6s %5s %5s | %5s %7s | %5s %5s | %s" % ("decider", "ok", "early", "vearly", "late", "miss", "extra", "per min", "ok %", "sweep", "ok by S/G")


def line(lab, r):
    c = r["c"]
    return "%-46s | %4d %5d %6d %5d %5d | %5d %7.1f | %4.0f%% %5d | %d/%d" % (lab, c["ok"], c["early"], c["vearly"], c["late"], c["miss"], r["extra"], r["extra"] / max(r["mins"], 1e-9),
                                                                           100.0 * c["ok"] / max(1, r["n"]), r["sw"], r["src"]["S"], r["src"]["G"])


def cols(T, v, level, lead=LEAD, skip=()):
    p = v["player"]; n = v["name"]
    return [i for i, t in enumerate(T) if abs(t["lead"] - lead) < 1e-6 and t["video"] not in skip and (
        level == "all" or (level == "own" and t["player"] == p) or (level == "loso" and t["player"] == p and t["video"] != n) or (level == "lopo" and t["player"] != p))]


def play(V, T, mode, level="own", gkey="Gc", lead=LEAD, skip=(), **kw):
    P = {}; sw = 0
    for name, v in V.items():
        P[name], s = run(v, mode, cols(T, v, level, lead, skip) if mode != "G" else None, gkey, delay=max(0.0, lead - LEAD), **kw); sw += s
    return P, sw


def load_me(mp4):
    p = mp4[:-4] + ".me.csv"
    if not os.path.exists(p): return None
    st = {}; seen_t = []; seen = []
    for r in csv.DictReader(open(p)):
        if r["state"] == "-": continue
        t = round(float(r["t_s"]), 4); st[t] = r["state"]
        if r["state"] == "seen":
            a = float(r["a"]); seen_t.append(t); seen.append(((float(r["mx"]) - float(r["sx"])) / a, (float(r["my"]) - float(r["sy"])) / (ASPECT * a)))
    return dict(state=st, seen_t=seen_t, seen=seen)


def pos_at(me, t):
    """What the tracker knew at scan t: state, relative position now and 150 ms ago (seen scans <= t, at most 60 ms old)."""
    if me is None: return "-", None, None
    def rel(x):
        i = bisect.bisect_right(me["seen_t"], x + 1e-5) - 1
        return np.array(me["seen"][i]) if i >= 0 and x - me["seen_t"][i] <= 0.06 else None
    return me["state"].get(round(t, 4), "-"), rel(t), rel(t - 0.15)


def check_video(v, rows, ts, S, M, rng, deep):
    """Truncation test of one video. -> dict of booleans."""
    res = {}; RT = v["scan_k"]
    cut = RT[int(len(RT) * rng.uniform(0.35, 0.8))] + 0.0005; k = bisect.bisect_right(RT, cut); n = int((ts <= cut).sum())
    ts2, S2 = posematch.stacks(v["mp4"], causal=True, upto=cut)
    res["stacks"] = len(ts2) == n and (n == 0 or (np.array_equal(ts2, ts[:n]) and np.array_equal(S2, S[:n])))
    v2 = dict(scan_k=RT[:k], sil_k=v["sil_k"][:n], D=posematch.dist(S2, M).astype(np.float32) if n else np.zeros((0, len(M)), np.float32), G={}, name=v["name"], player=v["player"])
    ok = True
    for key, lock, rej in GKEYS:
        v2["G"][key], ev = gap(rows[:k], lock, rej)
        ok &= [e for e in ev if e[0] <= cut - 0.03] == [e for e in v["Gev"][key] if e[0] <= cut - 0.03]
    res["rules"] = ok
    ok = True; own = [i for i in range(len(M))]
    for mode, kw in (("S", {}), ("U", {}), ("B", dict(H=0.3)), ("B", dict(H=0.15, watch=0.3, hmax=0.6)), ("S", dict(delay=0.07))):
        a = [p for p in run(v2, mode, own, **kw)[0] if p[0] <= cut - 0.03]; b = [p for p in run(v, mode, own, **kw)[0] if p[0] <= cut - 0.03]
        ok &= a == b
    res["decider"] = ok
    if deep:
        import poseclip
        sim = shotsim.run(v["mp4"], shotsim.Sim(), 0.0, cut)
        mine = ["%.4f,%s,%d,%.4f,%.4f,%.2f,%.2f,%.3f,%.3f,%.1f,%.4f,%.4f,%.5f,%d" % r for r in sim.rows]
        theirs = open(v["mp4"][:-4] + ".sim.csv").read().split("\n")[1:1 + len(mine)]
        res["frames"] = len(mine) == k and mine == theirs
        z = np.load(v["mp4"][:-4] + ".pose.npz"); pc = poseclip.extract(v["mp4"], limit=k)
        res["outline"] = all(np.array_equal(pc[key], z[key][:k]) for key in ("ok", "m", "off"))
    t3, S3 = posematch.stacks(v["mp4"], causal=False, upto=cut); tf, Sf = posematch.stacks(v["mp4"], causal=False)
    m = int((tf <= cut).sum())
    res["control_fails"] = not (len(t3) == m and np.array_equal(S3, Sf[:m]))
    return res


def build(vids, frozen, causal, scale=1.0, heldout=False):
    V = {}; T = []
    for v in vids:
        name = os.path.basename(v)[:-4]; player = name.split("_")[0]
        tr = truth.shots(v); first = {}
        if frozen is None or heldout:
            for t, meas, src, kind, k in tr["shots"]:
                if k >= 0 and k not in first: first[k] = t
        if frozen is None:
            ts, S = posematch.stacks(v, causal=True)
            for k, t in first.items():
                for L in LEADS:
                    if not len(ts): continue
                    j = int(np.argmin(np.abs(ts - (t - L))))
                    if abs(ts[j] - (t - L)) <= 0.03: T.append(dict(name="%s#%d" % (name, k), player=player, video=name, lead=L, vec=S[j].copy()))
            del S
        dur = tr["dur"]
        V[name] = dict(mp4=v, name=name, player=player, shots=[s[0] for s in tr["shots"]], held=[s[0] for s in tr["shots"] if first.get(s[4]) != s[0]],
                       kind={s[0]: s[3] for s in tr["shots"]}, excl=sorted(tr["darks"] + tr["unknown"] + [(0.0, 1.3), (dur - 0.5, dur)]), dur=dur)
        sys.stderr.write("."); sys.stderr.flush()
    if frozen is not None:
        z = np.load(frozen, allow_pickle=False)
        T = [dict(name=str(a), player=str(b), video=str(c), lead=float(d), vec=e) for a, b, c, d, e in zip(z["name"], z["player"], z["video"], z["lead"], z["vec"])]
    M = np.stack([t["vec"] for t in T]); rng = random.Random(11); chk = []; rep = [0, 0, 0]
    for i, (name, v) in enumerate(V.items()):
        rows = list(csv.DictReader(open(v["mp4"][:-4] + ".sim.csv")))
        ts, S = posematch.stacks(v["mp4"], causal=True, scale=scale)
        v["scan_k"] = [round(float(r["t_s"]), 4) for r in rows]; v["sil_k"] = [round(float(t), 4) for t in ts]
        v["D"] = posematch.dist(S, M).astype(np.float32) if len(ts) else np.zeros((0, len(M)), np.float32)
        v["G"] = {}; v["Gev"] = {}
        for key, lock, rej in GKEYS: v["G"][key], v["Gev"][key] = gap(rows, lock, rej)
        old = [float(r["t_s"]) for r in csv.DictReader(open(v["mp4"][:-4] + ".simev.csv", encoding="utf-8")) if r["event"] == "pressed"]
        rep[0] += len(old); rep[1] += len(v["G"]["X"]); rep[2] += sum(1 for t in old if any(abs(t - x) <= 0.02 for x in v["G"]["X"]))
        v["me"] = load_me(v["mp4"])
        if causal: chk.append((name, check_video(v, rows, ts, S, M, rng, i < 3)))
        del S
        sys.stderr.write(":"); sys.stderr.flush()
    sys.stderr.write("\n")
    for t in T: t.pop("vec")
    return dict(V=V, T=T, M=M if frozen is None else None, chk=chk, rep=rep)


def nested_thr(V, T, grid):
    """For each player: thr chosen on the OTHER players' videos (most ok with extra < 1 / min), applied to this player."""
    players = sorted(set(v["player"] for v in V.values())); tot = None; picks = []
    R = {th: play(V, T, "S", thr=th)[0] for th in grid}
    for p in players:
        best = None
        for th in grid:
            r = score(V, R[th], lambda v: v["player"] != p)
            key = (r["extra"] / max(r["mins"], 1e-9) < 1.0, r["c"]["ok"], -r["extra"])
            if best is None or key > best[0]: best = (key, th)
        r = score(V, R[best[1]], lambda v: v["player"] == p); picks.append((p, best[1], r["c"]["ok"], r["n"], r["extra"]))
        if tot is None: tot = r
        else:
            for k in CL: tot["c"][k] += r["c"][k]
            for k in ("extra", "mins", "sw", "n"): tot[k] += r[k]
            for k in ("S", "G"): tot["src"][k] += r["src"][k]
    return tot, picks


if __name__ == "__main__":
    a = sys.argv[1:]; thr = 0.15; cache = None; causal = False; save = None; frozen = None; log = None; dump = None; paths = []; scale = 1.0; heldout = False
    i = 0
    while i < len(a):
        if a[i] == "-thr": thr = float(a[i + 1]); i += 1
        elif a[i] == "-scale": scale = float(a[i + 1]); i += 1
        elif a[i] == "-heldout": heldout = True
        elif a[i] == "-norm": posematch.NORM = True
        elif a[i] == "-cache": cache = a[i + 1]; i += 1
        elif a[i] == "-save": save = a[i + 1]; i += 1
        elif a[i] == "-frozen": frozen = a[i + 1]; i += 1
        elif a[i] == "-log": log = a[i + 1]; i += 1
        elif a[i] == "-dump": dump = a[i + 1]; i += 1
        elif a[i] == "-causal": causal = True
        else: paths.append(a[i])
        i += 1
    vids = []
    for x in paths: vids += sorted(glob.glob(os.path.join(x, "*.mp4"))) if os.path.isdir(x) else [x]
    vids = [v for v in vids if all(os.path.exists(v[:-4] + e) for e in (".sim.csv", ".simev.csv", ".pose.npz"))]
    if cache and os.path.exists(cache): B = pickle.load(open(cache, "rb"))
    else:
        B = build(vids, frozen, causal, scale, heldout)
        if save:
            tv = [t for t in B["T"]]
            np.savez_compressed(save, name=np.array([t["name"] for t in tv]), player=np.array([t["player"] for t in tv]), video=np.array([t["video"] for t in tv]),
                                lead=np.array([t["lead"] for t in tv]), vec=B["M"], thr=np.array(thr))
        B["M"] = None
        if cache: pickle.dump(B, open(cache, "wb"))
    V = B["V"]; T = B["T"]
    if frozen: thr = float(np.load(frozen)["thr"])
    held = sum(len(v["held"]) for v in V.values()); mins = sum(v["dur"] - union_len(v["excl"]) for v in V.values()) / 60.0
    if posematch.NORM: print("EXPERIMENT -norm: every outline is resized by the screen row of the feet before matching (templates too)")
    if scale != 1.0: print("SIMULATION: the shooter is drawn x%.2f around the feet in the scored videos (templates unchanged)" % scale)
    print("videos %d, shots %d, scored %d (%s), templates %d at lead 230 ms from %d players, thr %.2f, scored minutes %.1f" % (
        len(V), sum(len(v["shots"]) for v in V.values()), held, ("frozen templates, " if frozen else "") + ("held-out: not the first rep of a skill" if heldout or not frozen else "every shot"),
        sum(1 for t in T if abs(t["lead"] - LEAD) < 1e-6), len(set(t["player"] for t in T)), thr, mins))
    print("replay check (rules fed from sim.csv vs the frame run): presses in simev %d, replayed %d, same within 20 ms %d" % tuple(B["rep"]))
    if B["chk"]:
        keys = ["stacks", "rules", "decider", "frames", "outline", "control_fails"]
        print("\ncausality (truncation test, one random cut per video): " + "  ".join("%s %d/%d" % (k, sum(1 for n, r in B["chk"] if r.get(k)), sum(1 for n, r in B["chk"] if k in r)) for k in keys))
        for n, r in B["chk"]:
            bad = [k for k in keys[:5] if k in r and not r[k]]
            if bad: print("   NOT CAUSAL: %s %s" % (n, bad))

    def show(lab, mode, **kw):
        P, sw = play(V, T, mode, thr=thr, **kw) if mode != "G" else play(V, T, mode, **kw)
        r = score(V, P); print(line(lab, r)); return P, r

    print("\n== 1. deciders, own-player templates, J 1.5 s (timing is a yardstick, not a verdict)\n" + HEAD)
    show("X  app rules as they are G(1.5, 0.4)", "G", gkey="X")
    show("X1 app rules, reject lock off", "G", gkey="X1")
    PA, RA = show("A  silhouette only", "S")
    show("U  silhouette or gap, first wins", "U")
    for H in (0.15, 0.30, 0.45, 0.60):
        Pb, Rb = show("B  silhouette first, gap held %d ms" % (H * 1000), "B", H=H)
        if H == 0.30: PB, RB = Pb, Rb
    for H, W, HM in ((0.10, 0.30, 0.6), (0.15, 0.30, 0.8)):
        show("Bw gap held %d ms, kept while d < %.2f (max %d)" % (H * 1000, W, HM * 1000), "B", H=H, watch=W, hmax=HM)
    print("\n== 1b. the same with other re-jump times J (unmeasured)\n" + HEAD)
    for J in (1.0, 0.6):
        show("A  silhouette only, J %.1f" % J, "S", J=J); show("U  first wins, J %.1f" % J, "U", J=J); show("B  gap held 300 ms, J %.1f" % J, "B", H=0.30, J=J)
    print("\n== 2. which templates the video may use (no template for the skill / for the player)\n" + HEAD)
    for level, txt in (("own", "own player (incl. this skill)"), ("loso", "own player, NOT this skill"), ("lopo", "other players only"), ("all", "every player")):
        show("A  silhouette only, %s" % txt, "S", level=level)
        show("B  gap held 300 ms, %s" % txt, "B", level=level, H=0.30)
        show("U  first wins, %s" % txt, "U", level=level)
    print("   gap rules alone (what an unknown player gets if the silhouette is switched off): rows X / X1 above")
    print("   per player, treated as a player WITHOUT templates (ok / scored, extra):")
    PXl = play(V, T, "G", gkey="X")[0]; PAl = play(V, T, "S", level="lopo", thr=thr)[0]; PUl = play(V, T, "U", level="lopo", thr=thr)[0]
    for p in sorted(set(v["player"] for v in V.values())):
        f = lambda P: (lambda r: "%2d/%d, %2d" % (r["c"]["ok"], r["n"], r["extra"]))(score(V, P, lambda v: v["player"] == p))
        print("      %-11s gap rules alone %s | silhouette with other players' templates %s | both, first wins %s" % (p, f(PXl), f(PAl), f(PUl)))
    STAND = ("doncic_r4_c2", "curry_r3_c1", "ayla_r3_c1")
    print("\n== 2b. without the three standing-shot templates (%s)\n%s" % (", ".join(STAND), HEAD))
    show("A  silhouette only", "S", skip=STAND); show("B  gap held 300 ms", "B", H=0.30, skip=STAND); show("U  first wins", "U", skip=STAND)
    print("\n== 3. threshold chosen without the scored player (nested: thr picked on the other players, extra < 1 / min)\n" + HEAD)
    if len(set(v["player"] for v in V.values())) > 1 and not frozen:
        tot, picks = nested_thr(V, T, [0.10, 0.12, 0.14, 0.15, 0.16, 0.18, 0.20, 0.22, 0.25])
        print(line("A  silhouette only, nested thr", tot)); print("   " + "  ".join("%s thr %.2f ok %d/%d extra %d" % p for p in picks))
    print("\n== 4. how early can it be told apart: template taken at release - L, press L - 230 ms after the match\n" + HEAD)
    for L in LEADS: show("A  silhouette only, template at -%d ms" % (L * 1000), "S", lead=L)
    print("\n== 5. by player and by kind (A silhouette only | B gap held 300 ms | X app rules)")
    PX = play(V, T, "G", gkey="X")[0]
    for lab, sel in [(p, (lambda v, p=p: v["player"] == p)) for p in sorted(set(v["player"] for v in V.values()))]:
        ra, rb, rx = score(V, PA, sel), score(V, PB, sel), score(V, PX, sel)
        print("   %-11s shots %3d min %4.1f | A ok %2d extra %2d | B ok %2d extra %2d | X ok %2d extra %2d" % (lab, ra["n"], ra["mins"], ra["c"]["ok"], ra["extra"], rb["c"]["ok"], rb["extra"], rx["c"]["ok"], rx["extra"]))
    for kd in ("D", "P", "pre"):
        f = lambda r: "%d/%d" % (sum(1 for d in r["det"] if d[2] == kd and d[4] == "ok"), sum(1 for d in r["det"] if d[2] == kd))
        print("   kind %-3s | A ok %s | B ok %s | X ok %s" % (kd, f(RA), f(RB), f(score(V, PX))))
    e = sorted(round(d[3] * 1000) for d in RA["det"] if d[3] is not None)
    if e: print("   A press - release (ms), all scored shots with a press: p10 %d median %d p90 %d" % (e[len(e) // 10], e[len(e) // 2], e[len(e) * 9 // 10]))
    # ---- position at the press (recorded, never used)
    print("\n== 6. where my player stood at the press (metrack, seen scans at or before the press; recorded only)")
    rowsL = []
    for lab, P in (("A", PA), ("B", PB)):
        for name, v in V.items():
            for t, w in P[name]:
                st, cur, old = pos_at(v["me"], t)
                near = [x for x in v["shots"] if -1.0 <= t - x <= 0.1]
                off = min((t - x for x in near), key=lambda q: abs(q + LEAD)) if near else None
                pred = cur + (cur - old) / 0.15 * LEAD if cur is not None and old is not None else None
                rowsL.append((lab, name, t, w, off, klass(off) if off is not None else "extra", st, cur, pred))
    for lab in ("A", "B"):
        r = [q for q in rowsL if q[0] == lab]; c = {}
        for q in r: c[q[6]] = c.get(q[6], 0) + 1
        print("   %s presses %d | tracker at the press: %s" % (lab, len(r), "  ".join("%s %d" % kv for kv in sorted(c.items()))))
        for k in ("ok", "early", "vearly", "late", "extra"):
            d = [float(np.hypot(*q[7])) for q in r if q[5] == k and q[7] is not None]
            if d: print("      %-6s n %3d with position %3d | distance (discs) p10 %.2f median %.2f p90 %.2f" % (k, sum(1 for q in r if q[5] == k), len(d), np.percentile(d, 10), np.median(d), np.percentile(d, 90)))
    if log:
        with open(log, "w", newline="") as f:
            wr = csv.writer(f); wr.writerow(["decider", "video", "t_s", "source", "press_minus_release_ms", "timing", "tracker", "dx", "dy", "dist", "pred_dx", "pred_dy", "pred_dist"])
            for lab, name, t, w, off, k, st, cur, pred in rowsL:
                g = lambda q: ["", "", ""] if q is None else ["%.2f" % q[0], "%.2f" % q[1], "%.2f" % float(np.hypot(*q))]
                wr.writerow([lab, name, "%.4f" % t, w, "" if off is None else round(off * 1000), k, st] + g(cur) + g(pred))
    if dump:
        with open(dump, "w") as f:
            for name, v in V.items():
                for t in v["held"]:
                    g = lambda P: ",".join("%+d%s" % (round((x - t) * 1000), w) for x, w in P[name] if -1.2 <= x - t <= 0.2) or "-"
                    dm = v["D"][:, cols(T, v, "own")].min(1) if len(v["sil_k"]) and cols(T, v, "own") else None
                    lo = min((float(dm[j]), round((x - t) * 1000)) for j, x in enumerate(v["sil_k"]) if -0.6 <= x - t <= 0.1) if dm is not None and any(-0.6 <= x - t <= 0.1 for x in v["sil_k"]) else None
                    f.write("%-18s %7.2f %-3s | A %-12s | B %-12s | X %-12s | Gc %-22s | lowest d %s\n" % (name, t, v["kind"][t], g(PA), g(PB), g(PX),
                            ",".join("%+d" % round((x - t) * 1000) for x in v["G"]["Gc"] if -1.2 <= x - t <= 0.2) or "-", "%.3f@%+d" % lo if lo else "-"))
            f.write("\nextra presses of A:\n")
            for name, v in V.items():
                for x, w in PA[name]:
                    if any(-1.0 <= x - t <= 0.1 for t in v["shots"]) or any(a <= x <= b for a, b in v["excl"]): continue
                    f.write("%-18s %7.2f\n" % (name, x))
