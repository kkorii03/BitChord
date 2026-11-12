import sys, os, csv, re
# risenear.py <game folder> <sessrel output txt> [-ev rise2] [-lock 1.5]
# What would "press on <event> only when the ball is next to my player" (NearGate of vc1104, replayed from matchup.csv)
# have done in this match? Events = shot_events rows of that name (rise2 = the record-only rise rule W 0.12 D 4.0, with
# x2/y2 in its detail) or "pressed" (the app's real presses; ball = the disc of shot.csv at that moment).
# Per release of sessrel.py: the nearest kept press to release - 0.26 s, graded on the EVENT clock with the block
# window of HANDOFF 0-4z7 (the touch goes out about 40 ms after the event; blocks were seen 181..265 ms before the
# release on the touch clock): win -305..-220 ms, contest -220..-150, early -600..-305, vearly < -600, late -150..+100.
# Timing only. Not a game verdict, and a release is ANY opponent's shot.
a = sys.argv[1:]; g = a[0]; EV = a[a.index("-ev") + 1] if "-ev" in a else "rise2"; LOCK = float(a[a.index("-lock") + 1]) if "-lock" in a else 1.5
rel = []
for l in open(a[1], encoding="utf-8"):
    m = re.match(r"\s+(\d+\.\d+)\s+(firm|noisy|dim)", l)
    if m: rel.append((float(m.group(1)), m.group(2)))
M = [(float(r["t_s"]), float(r["me_x"]), float(r["me_y"])) for r in csv.DictReader(open(os.path.join(g, "matchup.csv"), encoding="utf-8")) if float(r["me_x"]) >= 0]
S = []
with open(os.path.join(g, "shot.csv"), encoding="utf-8") as f:
    for r in csv.DictReader(f):
        if r.get("d2") == "1": S.append((float(r["t_s"]), float(r["x2"]), float(r["y2"])))
        elif r["disc"] == "1": S.append((float(r["t_s"]), float(r["x"]), float(r["y"])))
def med(v): v = sorted(v); return v[len(v) // 2]
def near(t, x, y):
    c = [m for m in M if m[0] <= t and t - m[0] <= 0.4][-3:]
    if not c: return -1, 0.0, 0.0
    up = med([m[2] for m in c]) / 540 - y; side = abs(med([m[1] for m in c]) / 1170 - x)
    return (1 if up <= 0.36 and side <= 0.14 else 0), up, side
E = []
for e in csv.DictReader(open(os.path.join(g, "shot_events.csv"), encoding="utf-8")):
    if e["event"] != EV: continue
    t = float(e["t_s"]); mx = re.search(r"x2=([\d.]+) y2=([\d.]+)", e["detail"])
    if mx: x, y = float(mx.group(1)), float(mx.group(2))
    else:
        c = [s for s in S if abs(s[0] - t) <= 0.08]
        if not c: E.append((t, -1, 0.0, 0.0)); continue
        s = min(c, key=lambda s: abs(s[0] - t)); x, y = s[1], s[2]
    E.append((t,) + near(t, x, y))
if "-rule" in a:
    # -rule W D: instead of logged events, replay the rise rule (mvel.py presses(), = ShotWatcher.rise2Press) on the d2
    # track of shot.csv (1560 x 720 capture) for any W and D. A trigger that is not near my player locks for 0.4 s
    # only (ShotWatcher.reject), a press for 1.5 s. EV is ignored.
    import numpy as np
    W = float(a[a.index("-rule") + 1]); D = float(a[a.index("-rule") + 2]); F = []
    with open(os.path.join(g, "shot.csv"), encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["side"] == "def" and r.get("d2") == "1": F.append((float(r["t_s"]), float(r["x2"]) * 1560.0, float(r["y2"]) * 720.0, (int(r["n2"]) / np.pi) ** 0.5, float(r["ring2"])))
    t, x, y, rr, ring = np.array(F, np.float64).T; E = []; EALL = []; last = -9.0; lastall = -9.0; COND = []
    for i in range(len(t)):
        if ring[i] < 0.3: continue
        j = int(np.searchsorted(t, t[i] - W))
        if j >= i or abs(t[j] - (t[i] - W)) > 0.02 or t[i] - t[j - 1 if j else 0] > W + 0.06: continue
        q = int(np.searchsorted(t, t[i] - 0.5)); R = float(np.median(rr[q:i + 1]))
        if not 0.7 * R <= rr[j] <= 1.4 * R or not 0.7 * R <= rr[i] <= 1.4 * R: continue
        rise = (y[j] - y[i]) / R
        if not (rise >= D and abs(x[i] - x[j]) / R <= 1.5 * rise): continue
        n = near(t[i], x[i] / 1560.0, y[i] / 720.0); COND.append((float(t[i]),) + n)
        if t[i] - lastall >= 1.5: lastall = float(t[i]); EALL.append((lastall,) + n)
        if t[i] - last >= 1.5:
            if n[0] == 1: last = float(t[i]); E.append((last,) + n)
            else: last = float(t[i]) - 1.1
    EV = "rise W %.2f D %.1f" % (W, D); LOCK = 0.0
    ENEAR = E; E = EALL
else: ENEAR = None
def grade(press):
    cnt = {}; used = set(); rows = []
    for R, src in rel:
        c = [p for p in press if R - 1.0 <= p <= R + 0.1]
        if not c: k = "none"; e = None
        else:
            e = min(c, key=lambda p: abs(p - (R - 0.26))); used.add(e); d = e - R
            k = "win" if -0.305 <= d <= -0.22 else "contest" if -0.22 < d <= -0.15 else "early" if -0.6 <= d < -0.305 else "vearly" if d < -0.6 else "late"
        cnt[k] = cnt.get(k, 0) + 1; rows.append((R, src, k, None if e is None else round((e - R) * 1000)))
    return cnt, [p for p in press if p not in used], rows
def lock(ts):
    out = []
    for t in ts:
        if not out or t - out[-1] >= LOCK: out.append(t)
    return out
print("%s: %d events of '%s' (near 1: %d, 0: %d, unknown: %d), %d releases" % (os.path.basename(g), len(E), EV, sum(1 for e in E if e[1] == 1), sum(1 for e in E if e[1] == 0), sum(1 for e in E if e[1] < 0), len(rel)))
for lab, ts in (("all events", [e[0] for e in E]), ("near only", [e[0] for e in ENEAR] if ENEAR is not None else lock([e[0] for e in E if e[1] == 1]))):
    cnt, extra, rows = grade(ts)
    print("  %-10s presses %2d | %s | belongs to no release %d" % (lab, len(ts), " ".join("%s %d" % (k, cnt.get(k, 0)) for k in ("win", "contest", "early", "vearly", "late", "none")), len(extra)))
    if lab == "near only":
        print("     per release: " + ", ".join("%.2f%s %s%s" % (R, "" if src == "firm" else "~", k, "" if d is None else " %+d" % d) for R, src, k, d in rows if k != "none"))
        print("     no release : " + " ".join("%.2f" % p for p in extra))
if "-rule" in a and "-chain" in a:
    # -chain: the rise switch AND the near switch on, next to the app's own chain as it was logged in this match
    # (gap / fresh / up / crouch triggers with their logged outcome). Locks as in ShotWatcher: 1.5 s after a trigger that
    # pressed, 0.4 s after a rejected one, no rise press while a chain trigger waits for its confirmation.
    rows = list(csv.DictReader(open(os.path.join(g, "shot_events.csv"), encoding="utf-8"))); items = [(c[0], 0, "rise", c[1], 0.0) for c in COND]
    for i, e in enumerate(rows):
        if e["event"] != "shot_trigger" or e["detail"].split(" ")[0] not in ("gap", "fresh", "up", "crouch", "early"): continue
        T = float(e["t_s"]); out = None
        for q in rows[i + 1:]:
            tq = float(q["t_s"])
            if tq - T > 0.6: break
            if q["event"] == "pressed": out = tq; break
            if q["event"].startswith("rejected"): break
        c = [s for s in S if abs(s[0] - T) <= 0.08]
        n = near(T, *min(c, key=lambda s: abs(s[0] - T))[1:])[0] if c else -1
        items.append((T, 1, "chain", n, out))
    items.sort(); lastf = -9.0; pend = -9.0; P = []
    for T, _, kind, n, out in items:
        if T - lastf < 1.5: continue
        if kind == "rise":
            if T < pend: continue
            if n == 1: lastf = T; P.append((T, "rise"))
            else: lastf = T - 1.1
        elif out is None or n != 1: lastf = T - 1.1
        else: lastf = T; pend = out; P.append((out, "chain"))
    cnt, extra, rws = grade([p[0] for p in P]); src = dict(P)
    print("  rise + near + the logged chain: presses %d (rise %d, chain %d) | %s | belongs to no release %d" % (len(P), sum(1 for p in P if p[1] == "rise"), sum(1 for p in P if p[1] == "chain"),
          " ".join("%s %d" % (k, cnt.get(k, 0)) for k in ("win", "contest", "early", "vearly", "late", "none")), len(extra)))
    print("     per release: " + ", ".join("%.2f%s %s %+d" % (R, "" if s == "firm" else "~", k, d) for R, s, k, d in rws if k != "none"))
    print("     no release : " + " ".join("%.2f(%s)" % (p, src[p]) for p in extra))
