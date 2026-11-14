"""The FROZEN practice-court scorer on the match shot clips of mclip.py (HANDOFF 0-4v). Nothing is fitted here.

mclipscore.py <model.npz> <clips folder> [-scale 100] [-rel 0.0] [-series out.csv]
For every clip: silhouette stacks (posematch.stacks, causal), learnapply.py's features and press rule (score >= frozen
thr, re-armed below thr - 0.5 for the MLP, 1.5 s lock, frozen delay). Times are printed relative to the label change
"전면 블록" -> "리바운드" (label_s) + rel, where rel = (release - label change), measured by eye on a few shots.
decide.py's yardstick on (press - release): ok -330..-150 ms, early -600..-330, very early < -600, late -150..+100.
"""
import sys, os, csv, glob
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import posematch

a = sys.argv[1:]; Z = np.load(a[0]); D = a[1]; THR = float(Z["thr"])
SC = a[a.index("-scale") + 1] if "-scale" in a else "100"; REL = float(a[a.index("-rel") + 1]) if "-rel" in a else 0.0
if "w1" in Z.files:
    DELAY = float(Z["delay"]); REARM = 0.5
    def f(x): return np.maximum(((x - Z["mu"]) / Z["sd"]) @ Z["w1"] + Z["b1"], 0) @ Z["w2"] + Z["b2"][0]
else:
    DELAY = 0.0; REARM = 0.1
    def f(x): return np.hstack([x, np.ones((len(x), 1), np.float32)]) @ Z["w"]
KEY = {}                    # -key <folder of mrel.py -key csvs>: the release of every shot instead of one -rel for all
if "-key" in a:
    for p in glob.glob(os.path.join(a[a.index("-key") + 1], "*.key.csv")):
        for r in csv.DictReader(open(p, encoding="utf-8")): KEY[(r["name"], int(r["k"]))] = (float(r["release_rel"]) if r["release_rel"] else None, r["kind"], r["src"])
kinds = {}
ser = csv.writer(open(a[a.index("-series") + 1], "w", newline="")) if "-series" in a else None
if ser: ser.writerow(["clip", "t_rel_s", "score"])
cnt = {"ok": 0, "early": 0, "vearly": 0, "late": 0, "miss": 0, "no anchor": 0}; extra = 0; n = 0
for meta in sorted(glob.glob(os.path.join(D, "*.clips.csv"))):
    name = os.path.basename(meta)[:-10]
    for r in csv.DictReader(open(meta)):
        k = int(r["k"]); n += 1
        ref = (float(r["label_s"]) if float(r["label_s"]) > 0 else float(r["shot_s"])) + REL; kind = ""
        if KEY:
            kr = KEY.get((name, k), (None, "", "none"))
            if kr[0] is None: print("%s %2d  no release in the key" % (name, k)); kinds["no key"] = kinds.get("no key", 0) + 1; continue
            ref = ref - REL + kr[0]; kind = kr[1] + "/" + kr[2]
        p = os.path.join(D, "%s_%02d_s%s" % (name, k, SC))
        if not os.path.exists(p + ".pose.npz"):
            cnt["no anchor"] += 1; print("%s %2d  %7.1f | no ball ring before the shot" % (name, k, ref)); continue
        t, S = posematch.stacks(p + ".mp4", causal=True); press = []; q = np.zeros(0)
        if len(t):
            x = S.reshape(len(S), 8, 78, 32).reshape(len(S), 8, 26, 3, 16, 2).mean((3, 5)).reshape(len(S), -1).astype(np.float32)
            q = f(x); armed = True; last = -9.0
            for tt, s in zip(t, q):
                if ser: ser.writerow(["%s_%02d" % (name, k), round(float(tt) - ref, 4), round(float(s), 3)])
                if armed and s >= THR:
                    armed = False
                    if tt - last >= 1.5: last = float(tt); press.append(float(tt) + DELAY - ref)
                elif s < THR - REARM: armed = True
        e = min(press, key=lambda z: abs(z + 0.23)) if press else None
        c = "miss" if e is None or not -1.0 <= e <= 0.1 else "ok" if -0.33 <= e <= -0.15 else "early" if -0.6 <= e < -0.33 else "vearly" if e < -0.6 else "late"
        cnt[c] += 1; extra += len([z for z in press if z is not e or c == "miss"])
        if kind: kinds[kind.split("/")[0] + " " + c] = kinds.get(kind.split("/")[0] + " " + c, 0) + 1; c = c + "  " + kind
        w = (t >= ref - 0.6) & (t <= ref + 0.1)
        print("%s %2d  %7.1f | stacks %3d (last 0.6 s: %2d) | peak %5.1f at %+.2f s | presses %s | %s" % (
            name, k, ref, len(t), int(w.sum()), float(q[w].max()) if w.any() else float("nan"), float(t[w][int(np.argmax(q[w]))] - ref) if w.any() else float("nan"),
            " ".join("%+.2f" % z for z in press) or "-", c))
print("scale %s, rel %s, thr %.1f, delay %.0f ms: %d shots | %s | presses elsewhere %d" % (SC, "per shot (key)" if KEY else "%+.2f s" % REL, THR, 1000 * DELAY, n, ", ".join("%s %d" % q for q in cnt.items()), extra))
if kinds: print("by kind: " + ", ".join("%s %d" % q for q in sorted(kinds.items())))
