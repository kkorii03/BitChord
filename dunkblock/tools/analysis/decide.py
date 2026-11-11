"""Ablation on the demonstration videos: which part changes how many real shots get a press at the right time.

decide.py [-thr D] <folder | mp4> ...      needs .sim.csv .simev.csv (shotsim.py), .pose.npz (poseclip.py), .me.csv (metrack.py)

Press sources (candidates):
  G  the app's gap rules, replayed from <name>.sim.csv (Sim.onRow) with a given press lock and reject lock.
     G(1.5, 0.4) is the app as it is. Checked against <name>.simev.csv (same presses?) before anything else.
  S  silhouette match (posematch.py stacks, centred WITHOUT future scans): templates = the first rep of each skill of the
     same player, at release - 230 ms; a press when the distance first drops below thr (re-armed above thr + 0.03).
Decider variants (one decider owns the button; a press within J seconds of the previous effective press is swallowed =
"my player is still in the air", J is NOT measured in the game: shown for 1.5 / 1.0 / 0.6 s):
  A   G(1.5, 0.4)                existing
  A1  G(1.5, 0)                  existing, reject lock removed
  A2  G(J, 0)                    existing, press lock = J
  C   S                          silhouette only
  D   S + G(J, 0)                both, first press wins (no decider)
  E   central decider            S presses at once; a G candidate (from G(0.25, 0), no locks) is held H ms and presses
                                 only if no S candidate came meanwhile
  +T  the same with the new tracker (metrack "seen" only) in place of findMe for the "too far" reject (ME_FAR)
  ceiling: a shot counts if ANY candidate of S or G(0.25, 0) lies in the ok window = the best any decider could do
           with these candidates.
Scored only on held-out shots (not the template rep). Windows relative to the release (a yardstick from the game's own
press at -208..-283 ms, NOT the game's verdict): ok -330..-150, early -600..-330, very early -1000..-600,
late -150..+100. extra = effective presses further than that from every shot, outside dark / unknown zones, the first
1.3 s and the last 0.5 s of a video.
"""
import sys, os, csv, glob, math
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shotsim, truth, posematch

LEAD = 0.230


class Replay(shotsim.Sim):
    def __init__(self, lock, rej, me=None):
        super().__init__()
        self.LOCK_NS = int(lock * 1e9); self.REJECT_LOCK_NS = int(rej * 1e9); self.me = me; self.nowS = 0.0

    def onRow(self, r):
        self.nowS = float(r["t_s"]); super().onRow(r)

    def findMe(self, sx, sy):
        self.meX = -1.0; self.meY = -1.0
        p = self.me.get(round(self.nowS, 4)) if self.me else None
        if p is None: return -1.0
        self.meX = p[0] / 1560.0; self.meY = p[1] / 720.0
        return math.hypot(self.meX - sx, (self.meY - sy) / self.aspect)


def me_table(mp4, mode):
    if mode is None or not os.path.exists(mp4[:-4] + ".me.csv"): return None
    d = {}
    for r in csv.DictReader(open(mp4[:-4] + ".me.csv")):
        if mode == "new" and r["state"] == "seen": d[round(float(r["t_s"]), 4)] = (float(r["mx"]), float(r["my"]))
        if mode == "old" and float(r["old_x"]) >= 0: d[round(float(r["t_s"]), 4)] = (float(r["old_x"]), float(r["old_y"]))
    return d


def gap(rows, lock, rej, me=None):
    s = Replay(lock, rej, me)
    for r in rows: s.onRow(r)
    return sorted(e[0] for e in s.events if e[1] == "pressed"), sum(1 for e in s.events if e[1] == "rejected_far")


def sil(ts, dmin, thr):
    out = []; armed = True
    for t, d in zip(ts, dmin):
        if armed and d < thr: out.append(float(t)); armed = False
        elif d > thr + 0.03: armed = True
    return out


def central(S, G, H):
    """One decider: a silhouette candidate presses at once; a gap candidate only opens a hold of H seconds and presses
    when the hold runs out, unless a silhouette candidate arrived in between (then that one presses)."""
    out = []; hold = None
    for t, k in sorted([(t, 0) for t in S] + [(t, 1) for t in G]):
        if hold is not None and t >= hold: out.append(hold); hold = None
        if k == 0: out.append(t); hold = None
        elif hold is None: hold = t + H
    if hold is not None: out.append(hold)
    return out


def effective(presses, J):
    eff = []; sw = 0
    for t in sorted(presses):
        if eff and t - eff[-1] < J: sw += 1
        else: eff.append(t)
    return eff, sw


def klass(e):
    return "ok" if -0.33 <= e <= -0.15 else "early" if -0.6 <= e < -0.33 else "vearly" if e < -0.6 else "late"


CL = ["ok", "early", "vearly", "late", "miss"]


def score(V, presses, J):
    """V: per video dict(held, shots, excl, dur). presses: name -> list. -> counts, extra, swallowed, minutes."""
    c = dict.fromkeys(CL, 0); extra = 0; sw = 0; secs = 0.0
    for name, v in V.items():
        eff, s = effective(presses[name], J); sw += s
        for t in v["held"]:
            d = [x - t for x in eff if -1.0 <= x - t <= 0.1]
            c[klass(min(d, key=lambda e: abs(e + LEAD))) if d else "miss"] += 1
        for x in eff:
            if any(-1.0 <= x - t <= 0.1 for t in v["shots"]) or any(a <= x <= b for a, b in v["excl"]): continue
            extra += 1
        secs += v["dur"] - sum(b - a for a, b in v["excl"])
    return c, extra, sw, secs / 60.0


if __name__ == "__main__":
    a = sys.argv[1:]; thr = 0.15; paths = []
    i = 0
    while i < len(a):
        if a[i] == "-thr": thr = float(a[i + 1]); i += 1
        else: paths.append(a[i])
        i += 1
    vids = []
    for x in paths: vids += sorted(glob.glob(os.path.join(x, "*.mp4"))) if os.path.isdir(x) else [x]
    vids = [v for v in vids if all(os.path.exists(v[:-4] + e) for e in (".sim.csv", ".simev.csv", ".pose.npz"))]
    # ---- pass 1: answer sheet, templates (first rep of each skill), rows
    V = {}; tpl = {}                                           # tpl: player -> list of (name#k, vector)
    for v in vids:
        name = os.path.basename(v)[:-4]; player = name.split("_")[0]
        tr = truth.shots(v); ts, S = posematch.stacks(v, causal=True)
        first = {}
        for t, meas, src, kind, k in tr["shots"]:
            if k >= 0 and k not in first: first[k] = t
        for k, t in first.items():
            if not len(ts): continue
            j = int(np.argmin(np.abs(ts - (t - LEAD))))
            if abs(ts[j] - (t - LEAD)) <= 0.03: tpl.setdefault(player, []).append(("%s#%d" % (name, k), S[j].copy()))
        dur = tr["dur"]
        V[name] = dict(mp4=v, player=player, shots=[s[0] for s in tr["shots"]], held=[s[0] for s in tr["shots"] if first.get(s[4]) != s[0]],
                       excl=sorted(tr["darks"] + tr["unknown"] + [(0.0, 1.3), (dur - 0.5, dur)]), dur=dur)
        del S
    print("videos %d, shots %d, held-out %d, templates %d (own player only, causal centring, thr %.2f)" % (
        len(V), sum(len(v["shots"]) for v in V.values()), sum(len(v["held"]) for v in V.values()), sum(len(x) for x in tpl.values()), thr))
    # ---- pass 2: candidates per video
    P = {}; farN = {}; chk = [0, 0, 0]
    for name, v in V.items():
        rows = list(csv.DictReader(open(v["mp4"][:-4] + ".sim.csv")))
        ev = [(float(r["t_s"]), r["event"]) for r in csv.DictReader(open(v["mp4"][:-4] + ".simev.csv", encoding="utf-8"))]
        old = [t for t, n in ev if n == "pressed"]
        g0, _ = gap(rows, 1.5, 0.4, me_table(v["mp4"], "old"))
        chk[0] += len(old); chk[1] += len(g0); chk[2] += sum(1 for t in old if any(abs(t - x) <= 0.02 for x in g0))
        ts, S = posematch.stacks(v["mp4"], causal=True); T = tpl.get(v["player"], [])
        if len(ts) and T:
            D = posematch.dist(S, np.stack([x[1] for x in T])); s = sil(ts, D.min(1), thr)
        else: s = []
        del S
        new = me_table(v["mp4"], "new")
        P[name] = dict(S=s)
        for key, lock, rej, me in (("A", 1.5, 0.4, None), ("A1", 1.5, 0.0, None), ("G1.0", 1.0, 0.0, None), ("G0.6", 0.6, 0.0, None), ("Gc", 0.25, 0.0, None),
                                   ("AT", 1.5, 0.4, new), ("G1.0T", 1.0, 0.0, new), ("G0.6T", 0.6, 0.0, new), ("G1.5T", 1.5, 0.0, new)):
            P[name][key], f = gap(rows, lock, rej, me)
            farN[key] = farN.get(key, 0) + f
        sys.stderr.write("."); sys.stderr.flush()
    print("\nreplay check (rules fed from sim.csv vs the frame run): presses in simev %d, replayed %d, same within 20 ms %d" % tuple(chk))
    print("'too far' rejects with the new tracker: existing rules %d (old findMe in the frame run: see rejected_far in simev)" % farN["AT"])
    held = sum(len(v["held"]) for v in V.values())
    print("\nheld-out shots %d" % held)
    print("%-34s %4s | %4s %5s %6s %5s %5s | %5s %8s %9s" % ("variant", "J", "ok", "early", "vearly", "late", "miss", "extra", "per min", "swallowed"))
    for J in (1.5, 1.0, 0.6):
        gj = {1.5: "A1", 1.0: "G1.0", 0.6: "G0.6"}[J]; gt = {1.5: "G1.5T", 1.0: "G1.0T", 0.6: "G0.6T"}[J]
        for lab, f in (("A  existing G(1.5,0.4)", lambda p: p["A"]), ("A1 G(1.5,0) no reject lock", lambda p: p["A1"]), ("A2 G(J,0) press lock = J", lambda p: p[gj]),
                       ("C  silhouette only", lambda p: p["S"]), ("D  silhouette + G(J,0)", lambda p: p["S"] + p[gj]),
                       ("E  central: S now, G held 150 ms", lambda p: central(p["S"], p["Gc"], 0.15)), ("E  central: S now, G held 300 ms", lambda p: central(p["S"], p["Gc"], 0.30)),
                       ("E  central: S now, G held 450 ms", lambda p: central(p["S"], p["Gc"], 0.45)),
                       ("A+T existing + new tracker", lambda p: p["AT"]), ("D+T silhouette + G(J,0) + tracker", lambda p: p["S"] + p[gt])):
            c, extra, sw, mins = score(V, {n: f(p) for n, p in P.items()}, J)
            print("%-34s %4.1f | %4d %5d %6d %5d %5d | %5d %8.1f %9d" % (lab, J, c["ok"], c["early"], c["vearly"], c["late"], c["miss"], extra, extra / mins, sw))
        print()
    for lab, f in (("G candidates only", lambda p: p["Gc"]), ("S candidates only", lambda p: p["S"]), ("S + G candidates", lambda p: p["S"] + p["Gc"])):
        n = sum(1 for name, v in V.items() for t in v["held"] if any(-0.33 <= x - t <= -0.15 for x in f(P[name])))
        print("ceiling (a perfect decider), %-18s: %d / %d shots have a candidate in the ok window" % (lab, n, held))
    out = os.environ.get("DECIDE_DUMP")
    if out:
        with open(out, "w") as f:
            for name, v in V.items():
                for t in v["held"]:
                    f.write("%s %.2f | %s\n" % (name, t, "  ".join("%s:%s" % (k, ",".join("%+d" % round((x - t) * 1000) for x in P[name][k] if -1.2 <= x - t <= 0.2)) for k in ("A", "Gc", "S"))))
