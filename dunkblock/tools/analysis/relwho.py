import sys, os, csv, re, math
# relwho.py <game folder> <sessrel output txt> : for every release sessrel.py found on defence: who held the ball
# (class of the name tag nearest to the ball holder's feet, voted over the scans of the last 0.8 s), where my ring was
# against the ball (NearGate up / side at release - 0.25 s), and what the app did from -1.5 s to +0.3 s.
g = sys.argv[1]
rel = []
for l in open(sys.argv[2], encoding="utf-8"):
    m = re.match(r"\s+(\d+\.\d+)\s+(firm|noisy)", l)
    if m: rel.append((float(m.group(1)), m.group(2)))
S = []
with open(os.path.join(g, "shot.csv"), encoding="utf-8") as f:
    for r in csv.DictReader(f):
        if r["side"] != "def": continue
        t = float(r["t_s"])
        if r.get("d2") == "1": S.append((t, float(r["x2"]), float(r["y2"]), float(r["ring_y"])))
        elif r["disc"] == "1": S.append((t, float(r["x"]), float(r["y"]), float(r["ring_y"])))
M = []
for r in csv.DictReader(open(os.path.join(g, "matchup.csv"), encoding="utf-8")):
    tags = [(q.split(":")[0], float(q.split(":")[1]), float(q.split(":")[2])) for q in r["tags"].split(";") if q]
    M.append((float(r["t_s"]), float(r["me_x"]), float(r["me_y"]), tags))
E = [(float(e["t_s"]), e["event"], e["detail"].split(" ")[0]) for e in csv.DictReader(open(os.path.join(g, "shot_events.csv"), encoding="utf-8"))]
def med(v): v = sorted(v); return v[len(v) // 2]
print("%8s %-5s | %-4s %-9s | %5s %5s %4s | app (ms against the release)" % ("release", "src", "who", "votes", "up", "side", "near"))
for R, src in rel:
    d = [s for s in S if R - 0.6 <= s[0] <= R - 0.1]
    if not d: print("%8.2f %-5s | no disc" % (R, src)); continue
    votes = {}
    for t, mx, my, tags in M:
        if not (R - 0.8 <= t <= R): continue
        dd = [s for s in d if abs(s[0] - t) <= 0.08] or d
        x = med([s[1] for s in dd]) * 1170; ry = [s[3] for s in dd if s[3] > 0]
        fy = (med(ry) if ry else med([s[2] for s in dd]) + 0.28) * 540
        best = None
        for c, tx, ty in tags:
            if mx >= 0 and abs(tx - mx) < 45 and 0 <= ty - my < 70: continue      # my own name tag
            q = math.hypot(tx - x, (ty - fy - 25) * 1.5)
            if q < 90 and (best is None or q < best[0]): best = (q, c)
        if best: votes[best[1]] = votes.get(best[1], 0) + 1
    who = max(votes, key=votes.get) if votes else "?"
    dn = [s for s in d if abs(s[0] - (R - 0.25)) <= 0.06] or d
    bx = med([s[1] for s in dn]); by = med([s[2] for s in dn])
    c = [m for m in M if m[0] <= R - 0.25 and R - 0.25 - m[0] <= 0.4 and m[1] >= 0][-3:]
    if c:
        up = med([m[2] for m in c]) / 540 - by; side = abs(med([m[1] for m in c]) / 1170 - bx); near = "1" if up <= 0.36 and side <= 0.14 else "0"
    else: up = side = -1; near = "-1"
    ev = ["%s%s %+d" % (n.replace("shot_trigger", "trig:").replace("rejected_", "rej_"), k if n == "shot_trigger" else "", round((t - R) * 1000))
          for t, n, k in E if R - 1.5 <= t <= R + 0.3 and (n in ("shot_trigger", "pressed", "confirmed", "rise2", "arms_up") or n.startswith("rejected") or n.startswith("skill_dark") and not n.endswith("end"))]
    print("%8.2f %-5s | %-4s %-9s | %5.2f %5.2f %4s | %s" % (R, src, who, " ".join("%s%d" % kv for kv in sorted(votes.items())), up, side, near, ", ".join(ev)))
