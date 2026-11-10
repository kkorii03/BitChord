import sys, os, csv, glob
# darksim.py <game folder | *.scan.csv | folder of scan csv> ...
# Replays ShotWatcher.skillDark (vc1067) = "a signature skill darkens the whole screen": the same rule, line for line,
# on the slot2 luma of (a) a recorded bundle (shot.csv, 60 Hz, with the side column) or (b) a screen recording scanned by
# vidskill.py (every 2nd frame = 60 Hz, no side). Prints every onset (nth = 1, or 2.. for a dark that follows another
# within 0.7 s = the real shot after a fake), how long after the true onset it was seen, and the end of each dark.
# On match bundles the count per minute and the side at the onset say how often it would fire for nothing.

def sim(S):
    """S = [(t s, luma, side or None)] -> [dict(t, nth, base, on, end, low, why, def_)]"""
    L = [0.0] * 20; T = [0.0] * 20; n = 0
    dark = False; base = low = 0.0; on = end = 0.0; peak = 0.0; peakT = 0.0; nthc = 0
    lastDef = lastOff = -9.0; out = []; cur = None
    for t, l, side in S:
        if side == "def": lastDef = t
        elif side == "off": lastOff = t
        L = L[1:] + [l]; T = T[1:] + [t]; n += 1
        if n < 20 or l < 0: continue
        p1, p2 = L[18], L[17]
        if not dark:
            nth = 0
            # a fade: two falling scans, but never a jump (a pressed button or a new label drops 20-40 at once)
            falling = l < p1 - 0.8 and p1 < p2 - 0.8 and p2 - l <= 8
            recent = end != 0.0 and t - end < 0.7
            if recent and l > peak: peak = l; peakT = t
            if falling and recent:
                if l <= peak - 6: nth = nthc + 1; onT = peakT; b = base
            elif falling:
                pre = L[0:12]; b = sum(pre) / 12.0
                if max(pre) - min(pre) < 5 and l <= b - 8 and l <= min(pre) - 6 and b - l <= 20 and b >= 60:
                    nth = 1; onT = t
                    for i in range(19, 11, -1):
                        if L[i] < b - 1.5: onT = T[i]
            if nth:
                dark = True; base = b; low = l; on = onT; nthc = nth
                cur = dict(t=t, nth=nth, base=b, on=onT, luma=l, def_=(side is None) or (t - lastDef < 0.2 and t - lastOff > 2.0), end=None, low=l, why="")
                out.append(cur)
        else:
            if l < low: low = l; cur["low"] = l
            depth = base - low; why = ""
            if depth >= 0.2 * base and l >= low + 0.6 * depth: why = "up"
            elif depth < 0.2 * base and t - on > 0.3: why = "shallow"
            elif t - on > 3.0: why = "timeout"
            if why:
                dark = False; cur["end"] = t; cur["why"] = why
                if why == "up": end = t; peak = l; peakT = t
                else: end = 0.0
    return out

def load(p):
    if os.path.isdir(p) and os.path.exists(os.path.join(p, "shot.csv")):
        return [(float(r["t_s"]), float(r["slot2_luma"]), r["side"]) for r in csv.DictReader(open(os.path.join(p, "shot.csv")))], True
    R = list(csv.DictReader(open(p)))
    return [(float(r["t"]), float(r["slot"]), None) for r in R[::2]], False

if __name__ == "__main__":
    for a in sys.argv[1:]:
        ps = [a] if (a.endswith(".csv") or os.path.exists(os.path.join(a, "shot.csv"))) else sorted(glob.glob(os.path.join(a, "*.scan.csv")))
        for p in ps:
            S, bundle = load(p); o = sim(S)
            dur = S[-1][0] - S[0][0] if S else 0
            deep = [d for d in o if d["why"] != "shallow"]
            print("== %s  %.0f s  onsets %d (deep %d, on defence %d)  = %.2f deep-on-defence per minute" % (
                os.path.basename(p.rstrip("\\/"))[-40:], dur, len(o), len(deep), sum(1 for d in deep if d["def_"]), 60.0 * sum(1 for d in deep if d["def_"]) / max(dur, 1)))
            for d in o:
                print("   %8.3f nth=%d base %.1f seen +%3.0f ms after onset (luma %.1f)  low %.1f  end %s %s%s" % (
                    d["on"], d["nth"], d["base"], (d["t"] - d["on"]) * 1000, d["luma"], d["low"],
                    "+%.0f ms" % ((d["end"] - d["on"]) * 1000) if d["end"] else "-", d["why"], "" if d["def_"] else "  NOT-DEF"))
