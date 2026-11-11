"""How much of the defence time the app saw the ball disc, from an app session's shot.csv (HANDOFF 0-4x).

discseen.py <shot.csv> [-from s] [-to s] [-rel t1,t2,...]
For the app's finder (column disc) and, when the session has it, the ring-checked shadow finder (column d2, vc1088+):
  seconds on defence, seconds seen, lengths of the seen / unseen runs (a run ends when the state changes or 0.2 s pass
  without a row), and with -rel (release times in session seconds): the share of rows seen in the last second and in
  -0.35..-0.10 s before each release, and the number of releases with >= 73 % in that window.
With d2: rows where both saw a disc and how often they are the same place (within 0.03 of the width and height).
"""
import sys, csv, statistics as st


def runs_of(R, j):
    runs = []; cur = None
    for r in R:
        if r[1] != "def": cur = None; continue
        if cur is None or cur[2] != r[j] or r[0] - cur[1] > 0.2: cur = [r[0], r[0], r[j]]; runs.append(cur)
        cur[1] = r[0]
    return runs


def main():
    a = sys.argv[1:]; p = a[0]; a = a[1:]; t0 = 0.0; t1 = 1e9; rel = []
    while a:
        if a[0] == "-from": t0 = float(a[1])
        elif a[0] == "-to": t1 = float(a[1])
        elif a[0] == "-rel": rel = [float(q) for q in a[1].split(",")]
        else: raise SystemExit("unknown " + a[0])
        a = a[2:]
    rd = csv.DictReader(open(p, encoding="utf-8")); has2 = "d2" in rd.fieldnames
    R = []
    for r in rd:
        t = float(r["t_s"])
        if t0 <= t <= t1:
            R.append((t, r["side"], r["disc"] == "1", has2 and r["d2"] == "1", float(r["x"]), float(r["y"]),
                      float(r["x2"]) if has2 else -1.0, float(r["y2"]) if has2 else -1.0))
    D = [r for r in R if r[1] == "def"]; dt = st.median([b[0] - a_[0] for a_, b in zip(R, R[1:])]) if len(R) > 1 else 0.0
    print("%d rows, %d on defence = %.1f s (row step %.0f ms)" % (len(R), len(D), len(D) * dt, 1000 * dt))
    for name, j in (("disc", 2), ("d2", 3)) if has2 else (("disc", 2),):
        seen = sum(r[j] for r in D); runs = runs_of(R, j)
        on = [b - a_ + dt for a_, b, s in runs if s]; off = [b - a_ + dt for a_, b, s in runs if not s]
        print("%-4s seen %.1f s = %d%% of defence | %d seen runs (median %.2f s) | %d unseen runs (median %.2f s)" % (
            name, seen * dt, 100 * seen // max(1, len(D)), len(on), st.median(on) if on else 0, len(off), st.median(off) if off else 0))
        print("     unseen runs: " + ", ".join("%s %d (%.1f s)" % (lab, len([o for o in off if lo <= o < hi]), sum(o for o in off if lo <= o < hi))
              for lab, lo, hi in (("< 0.05 s", 0, 0.05), ("0.05-0.2", 0.05, 0.2), ("0.2-0.5", 0.2, 0.5), ("0.5-1", 0.5, 1.0), (">= 1 s", 1.0, 1e9))))
        if rel:
            good = 0; line = []
            for q in rel:
                A = [r for r in R if q - 1.0 <= r[0] < q]; W = [r for r in R if q - 0.35 <= r[0] <= q - 0.10]
                sw = 100 * sum(r[j] for r in W) // max(1, len(W)); good += sw >= 73
                line.append("%.2f: %d/%d" % (q, 100 * sum(r[j] for r in A) // max(1, len(A)), sw))
            print("     release: % seen in the last second / in -0.35..-0.10 | " + "  ".join(line))
            print("     releases with >= 73%% in -0.35..-0.10: %d of %d" % (good, len(rel)))
    if has2:
        both = [r for r in D if r[2] and r[3]]; same = sum(abs(r[4] - r[6]) <= 0.03 and abs(r[5] - r[7]) <= 0.03 for r in both)
        print("both saw a disc in %d rows, the same place in %d; only disc %d, only d2 %d, neither %d" % (
            len(both), same, sum(r[2] and not r[3] for r in D), sum(r[3] and not r[2] for r in D), sum(not r[2] and not r[3] for r in D)))


if __name__ == "__main__":
    main()
