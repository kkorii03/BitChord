import sys, os, glob, csv, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shotlib as L
# libctx.py <lib2.txt> <demo root>: what is on screen at each low "elsewhere" match (< 0.12)?
root = sys.argv[2]; n_shot = n_none = 0; rows = []
for ln in open(sys.argv[1], encoding="utf-8-sig"):
    if "| elsewhere:" not in ln: continue
    name = ln.split()[0]
    cand = glob.glob(os.path.join(root, "*", name + ".mp4")) + glob.glob(os.path.join(root, name + ".mp4"))
    if not cand: continue
    v = cand[0]
    ev = [(float(r["t_s"]), r["event"]) for r in csv.DictReader(open(v[:-4] + ".simev.csv", encoding="utf-8"))]
    e = L.vidskill.events(L.vidskill.scan(v)) or {}
    ex = [(b[1], "FLASH%d" % b[2]) for b in e.get("bursts", [])] + [(l[0], "GAMEPRESS") for l in e.get("lits", [])]
    for dk in e.get("darks", []):
        t0 = dk.get("on") if isinstance(dk, dict) else dk[0]
        if t0 is not None: ex.append((t0, "DARK"))
    segs = e.get("seg", [])
    for m in re.finditer(r"(\d\.\d+)@(\d+\.\d+)\(([^)]+)\)", ln.split("| elsewhere:")[1]):
        d = float(m.group(1)); t = float(m.group(2))
        if d >= 0.12: continue
        kind = next((s[2] for s in segs if s[0] <= t <= s[1]), "?")
        near = sorted((x - t, n) for x, n in ev + ex if -0.5 <= x - t <= 1.0 and n in ("sweep_rel", "pressed", "arms_up", "crouch", "GAMEPRESS", "DARK") or (-0.5 <= x - t <= 1.0 and n.startswith("FLASH")))
        shot = any(n in ("sweep_rel", "GAMEPRESS", "DARK") or n.startswith("FLASH") for dt, n in near)
        n_shot += shot; n_none += (not shot)
        rows.append("%-18s %.3f @%6.2f %s tpl=%-18s %s  %s" % (name, d, t, kind, m.group(3), "SHOT" if shot else "----", " ".join("%s%+d" % (n, round(dt * 1000)) for dt, n in near)))
print("\n".join(rows)); print("low elsewhere matches %d: near a real shot sign %d, nothing near %d" % (n_shot + n_none, n_shot, n_none))