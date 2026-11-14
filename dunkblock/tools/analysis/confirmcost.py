import sys, os, csv, re
import numpy as np
# confirmcost.py <game folder> [-rel t1,t2,...] : what the confirmation wait of ShotWatcher costs.
# Every shot_trigger of the session with its outcome (pressed at once = calm, confirmed after N ms, rejected_norise /
# rejected_xspan / rejected_far) and the time to the nearest release (hand list, else sessrel.releases).
# For each held trigger also: the rise of the disc (pendY - y) at each scan after the trigger, from shot.csv.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import sessrel
g = sys.argv[1].rstrip("\\/"); hand = None
if len(sys.argv) > 3 and sys.argv[2] == "-rel": hand = [float(x) for x in sys.argv[3].split(",")]
T = []; L = []; S = []; Y = []; D = []
for r in csv.DictReader(open(os.path.join(g, "shot.csv"), encoding="utf-8")):
    T.append(float(r["t_s"])); L.append(float(r["slot2_luma"])); S.append(r["side"]); Y.append(float(r["y"])); D.append(r["disc"] == "1")
T = np.array(T); L = np.array(L); Y = np.array(Y)
rel = hand if hand else [t for t, _ in sessrel.releases(T, L, S)]
ev = list(csv.DictReader(open(os.path.join(g, "shot_events.csv"), encoding="utf-8")))
E = [(float(e["t_s"]), e["event"], e.get("detail", "")) for e in ev]
print("== %s  releases %d (%s)" % (os.path.basename(g)[:15], len(rel), "hand" if hand else "auto"))
trig = [x for x in E if x[1] == "shot_trigger"]
for t, _, d in trig:
    kind = d.split(" ")[0]
    nxt = [x for x in E if 0 <= x[0] - t <= 0.45 and x[1] in ("pressed", "not_pressed", "would_press", "confirmed", "rejected_norise", "rejected_xspan", "rejected_far", "rejected_dim", "rejected")]
    out = "?"; after = None
    for x in nxt:
        if x[1].startswith("rejected"): out = x[1]; after = (x[0] - t) * 1000; break
        if x[1] == "confirmed": out = "confirmed"; after = (x[0] - t) * 1000; break
        if x[1] in ("pressed", "not_pressed", "would_press"): out = "direct " + x[1]; after = (x[0] - t) * 1000; break
    nr = min(rel, key=lambda q: abs(q - t)) if rel else None
    dr = (t - nr) * 1000 if nr is not None else 9e9
    m = re.search(r"xspan=([0-9.]+)", d); xs = m.group(1) if m else "-"
    i = int(np.searchsorted(T, t - 0.002)); y0 = Y[i] if i < len(Y) else -1
    rise = []
    for j in range(i + 1, min(i + 16, len(T))):
        if T[j] - t > 0.21: break
        rise.append("%d:%s" % (round((T[j] - t) * 1000), ("%+.3f" % (y0 - Y[j])) if D[j] else "x"))
    print("  %7.2f %-6s xspan %-5s -> %-16s %4.0f ms | release %s | %s" % (t, kind, xs, out, after if after is not None else -1,
          ("%+5.0f ms" % dr) if abs(dr) < 2500 else "  none", " ".join(rise)))
