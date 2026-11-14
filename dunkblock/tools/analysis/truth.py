"""Answer sheet of a demonstration recording: every shot in the video, from signals that do not depend on any recogniser.

truth.py <folder | mp4> ...        needs <name>.simev.csv (shotsim.py) and vidskill's scan

  step   release measured: the box-out slot step (`sweep_rel` of shotsim)
  lit    release estimated = the game's own press (block button lights up in a demonstration) + 230 ms
  A white flash is NOT used as a shot: most flashes are the end of a demonstration (1.2-3 s after the release) or an
  effect of the move (0.7 s before it). A flash that is neither a known shot's block flash nor such an ending becomes
  an "unknown" zone [on - 0.6 s, on + 0.3 s], left out of every count.
  dark   a player skill that darkens the screen: [on - 0.3 s, off + 1.0 s] is left out of every count
kind: pre = before the first demonstration banner (the previous skill's practice shot, see HANDOFF 0-4h), D, P.
shots(mp4) -> dict(shots=[(t_release, measured, src, kind, skill index or -1)], darks=[(a, b)], unknown=[(a, b)], dur, seg)
"""
import sys, os, csv, glob
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vidskill

LEAD = 0.230


def shots(mp4):
    ev = [(float(r["t_s"]), r["event"]) for r in csv.DictReader(open(mp4[:-4] + ".simev.csv", encoding="utf-8"))]
    e = vidskill.events(vidskill.scan(mp4)) or dict(seg=[], darks=[], bursts=[], lits=[], dur=0.0)
    seg = e["seg"]; ds = [s for s in seg if s[2] == "D"]
    darks = [(d["on"] - 0.3, d["off"] + 1.0) for d in e["darks"]]
    indark = lambda t: any(a <= t <= b for a, b in darks)
    out = []
    for t, n in ev:
        if n == "sweep_rel" and not indark(t): out.append([t, True, "step"])
    for on, dur, size in e["lits"]:
        if dur >= 1.0 or indark(on): continue
        if not any(s[2] == "D" and s[0] + 0.5 <= on <= s[1] + 0.3 for s in seg): continue
        if any(0 <= on - b[0] <= 0.6 for b in e["bursts"]): continue            # the button also blinks right after a transition flash
        if not any(on + 0.12 <= s[0] <= on + 0.40 for s in out): out.append([on + LEAD, False, "lit"])
    unknown = []
    steps = [s[0] for s in out]
    for on, peak, size in e["bursts"]:
        if indark(on): continue
        if any(on - 0.25 <= t <= on + 0.08 for t in steps): continue           # the block flash of a known shot
        if any(on - 3.0 <= t <= on - 1.2 for t in steps): continue             # the flash when a demonstration / rep ends
        unknown.append((on - 0.6, on + 0.3))                                   # an effect, or a shot whose step was missed
    out.sort()
    res = []
    for t, meas, src in out:
        if not ds or t < ds[0][0]: kind = "pre"; k = -1
        else:
            k = max(i for i, s in enumerate(ds) if s[0] <= t)
            kind = "D" if ds[k][0] - 0.3 <= t <= ds[k][1] + 0.3 else "P"
        res.append((t, meas, src, kind, k))
    return dict(shots=res, darks=darks, unknown=unknown, dur=e["dur"], seg=seg)


if __name__ == "__main__":
    tot = {}
    for a in sys.argv[1:]:
        for p in (sorted(glob.glob(os.path.join(a, "*.mp4"))) if os.path.isdir(a) else [a]):
            if not os.path.exists(p[:-4] + ".simev.csv"): continue
            s = shots(p)
            for q in s["shots"]: tot[(q[3], q[2])] = tot.get((q[3], q[2]), 0) + 1
            print("%-18s %5.1fs D:%s | %s%s" % (os.path.basename(p)[:-4], s["dur"], " ".join("%.1f-%.1f" % (x[0], x[1]) for x in s["seg"] if x[2] == "D"),
                  "  ".join("%s%d %.2f%s" % (q[3], q[4], q[0], "" if q[1] else "~" + q[2]) for q in s["shots"]),
                  ("  DARK " + " ".join("%.1f-%.1f" % d for d in s["darks"]) if s["darks"] else "") + ("  UNKNOWN " + " ".join("%.1f-%.1f" % d for d in s["unknown"]) if s["unknown"] else "")))
    print("shots by kind and source:", "  ".join("%s/%s %d" % (k[0], k[1], v) for k, v in sorted(tot.items())))
