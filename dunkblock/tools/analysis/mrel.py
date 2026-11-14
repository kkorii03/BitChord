"""The red ball disc around the opponent shots of a colour match recording, and a release time per shot (HANDOFF 0-4w).

mrel.py <mp4> <name.clips.csv from mclip.py> <out.rel.csv> [-from 3.0] [-to 0.0] [-strip folder k1,k2] [-swin 1.95 0.95] [-only k1,k2]
mrel.py -sum a.rel.csv ...
mrel.py -key <mp4> <name.clips.csv> <name.rel.csv> <out.key.csv>
Purpose: a release time for EVERY match shot instead of "label change - 1.33 s" (eye, 2 shots).
The game draws a red disc with a grey ring over the ball handler; when the ball leaves, the ring goes and the disc
shrinks to nothing within about 0.1 s.
  window  = the whole screen at full resolution minus the fixed HUD corners
  disc    = connected area of disc red (r >= 135, g <= 75, 10 <= b <= 125, r - g >= 90), roughly round
            (aspect 0.7-1.45, fill >= 0.55 of the bounding box), 12-130 px wide
  ring    = share of grey pixels (all channels >= 150, spread <= 45) on the circle at 1.12-1.38 radius
  track   = one disc at a time over [ref - from, ref + to]: start on a ringed disc (ring >= 0.45, r >= 13), follow the
            nearest candidate within 70 px + 1.2 radius, give up after 10 lost frames, jump to a ringed disc when the
            followed thing has had no ring for 6 frames
  events  = last full-size ringed frame before the disc falls away (t_shrink)
rel.csv: k, t_rel (frame time - ref; ref = label_s, or shot_s when the label was not seen), x, y (full-resolution px),
         r (equivalent radius = sqrt(area / pi)), ring, n (candidates in the window)
-key: two clocks. (1) the "수비" slot of ShotWatcher.readSlot2 steps about +10 luma when the game turns it into
"박스아웃" = the release (HANDOFF 0-4f); (2) the disc shrink starts SHRINK_LAG = 0.13 s after that (12 clean shots).
  src = both (the clocks agree) / step / shrink (one clock, inside ref - 1.65 .. ref - 0.2) / shrink? step? (doubtful) / none
  kind = jump (release >= 0.8 s before the label change) / rim (dunk, lay-up, tip)
key.csv: name, k, ref_s, release_s, release_rel, src, t_shrink, step_d, kind, x, y, R
"""
import sys, os, csv, math
import numpy as np, cv2

K5 = np.ones((5, 5), np.uint8); K3 = np.ones((3, 3), np.uint8)
ANG = np.linspace(0, 2 * math.pi, 48, endpoint=False)


def cands(c):
    b, g, r = [c[..., i].astype(np.int16) for i in range(3)]
    grey = ((np.minimum(np.minimum(b, g), r) >= 150) & (np.maximum(np.maximum(b, g), r) - np.minimum(np.minimum(b, g), r) <= 45))
    m = ((r >= 135) & (g <= 75) & (b >= 10) & (b <= 125) & (r - g >= 90)).astype(np.uint8)
    # The ball seams on the disc are dark lines: close them, then cut the grey ring out again (a plain close glued
    # the disc to the red outline of the shooter's hands across the ring).
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, K5) & ~cv2.dilate(grey.astype(np.uint8), K3)
    n, lab, st, cen = cv2.connectedComponentsWithStats(m, 4)
    out = []
    for i in range(1, n):
        x, y, w, h, a = st[i]
        if w < 12 or h < 12 or w > 130 or h > 130: continue
        if not 0.7 <= w / float(h) <= 1.45: continue
        if a < 0.55 * w * h: continue
        rr = math.sqrt(a / math.pi); cx, cy = cen[i]
        hit = 0; tot = 0
        for k in (1.12, 1.25, 1.38):
            xs = np.rint(cx + k * rr * np.cos(ANG)).astype(int); ys = np.rint(cy + k * rr * np.sin(ANG)).astype(int)
            ok = (xs >= 0) & (xs < c.shape[1]) & (ys >= 0) & (ys < c.shape[0])
            tot += int(ok.sum()); hit += int(grey[ys[ok], xs[ok]].sum())
        out.append((float(cx), float(cy), rr, hit / float(max(1, tot))))
    return out


def hud(fx, fy):
    """Fixed screen furniture (score, buttons, joystick), as fractions of the screen."""
    return fy < 0.085 or (fx < 0.25 and fy < 0.30) or (fx > 0.60 and fy < 0.14) or (fx > 0.70 and fy > 0.36) or (fx < 0.26 and 0.52 < fy < 0.95)


def events(F):
    """F: tracked frames (t, r, ring, x, y) of one shot. The shrink events: a frame at full radius (0.85-1.2 of the
    median of the 0.45 s before it, that median >= 11 px) that is the LAST full one: within the next 0.3 s the radius
    only falls (frames above 1.2 of the full radius = the disc glued to an effect, are skipped) and reaches 0.55 or
    less. Returns [(t_shrink, t_gone, R, held_s, x, y, gap_s)]: t_gone = last frame of the fall, gap_s = time to the
    first frame after t_shrink (large = the start of the fall was not seen, t_shrink is early by up to that much)."""
    out = []; n = len(F); i = 0
    while i < n:
        t, r = F[i][0], F[i][1]
        pf = [f for f in F[max(0, i - 60):i] if t - 0.45 <= f[0] <= t - 0.04]; pre = [f[1] for f in pf]
        if len(pre) >= 8 and float(np.median([f[2] for f in pf])) >= 0.35:      # a real disc has its grey ring before the fall
            R = float(np.median(pre))
            if R >= 11 and 0.85 * R <= r <= 1.2 * R and F[i][2] >= 0.3 and sum(1 for q in pre if 0.8 * R <= q <= 1.25 * R) >= 0.7 * len(pre):
                low = r; last = -1; ok = False; bad = False; first = -1
                for j in range(i + 1, n):
                    if F[j][0] - t > 0.3: break
                    if F[j][1] > 1.2 * R: continue
                    if F[j][1] >= 0.85 * R or F[j][1] > low + 1.5:
                        bad = not ok; break                     # after the fall the tracker may latch on to something else
                    if first < 0: first = j
                    low = min(low, F[j][1]); last = j
                    if F[j][1] <= 0.55 * R: ok = True
                if ok and not bad:
                    st = i
                    while st > 0 and F[st][0] - F[st - 1][0] <= 0.12 and 0.7 * R <= F[st - 1][1] <= 1.3 * R: st -= 1
                    out.append((t, F[last][0], R, t - F[st][0], F[i][3], F[i][4], F[first][0] - t)); i = last
        i += 1
    return out


def summary(paths):
    """mrel.py -sum a.rel.csv ...: per shot the shrink event nearest to ref - 1.25 s (see events)."""
    print("shot      R  t_shrink  t_gone  fall_s  gap_s  held_s    x    y   events")
    D = []
    for p in paths:
        name = os.path.basename(p).split(".")[0]; S = {}
        for r in csv.DictReader(open(p, encoding="utf-8")):
            if float(r["r"]) > 0: S.setdefault(int(r["k"]), []).append((float(r["t_rel"]), float(r["r"]), float(r["ring"]), float(r["x"]), float(r["y"])))
            else: S.setdefault(int(r["k"]), [])
        for k in sorted(S):
            E = events(S[k])
            if not E: print("%s%-3d  no shrink event (%d tracked frames)" % (name, k, len(S[k]))); continue
            e = E[-1]                      # the label (block -> rebound) follows the shot: the last shrink before it
            print("%s%-3d %5.1f   %+.3f  %+.3f   %.3f  %.3f    %.2f  %4.0f %4.0f   %d %s" % (name, k, e[2], e[0], e[1], e[1] - e[0], e[6], e[3], e[4], e[5], len(E), " ".join("%+.2f" % q[0] for q in E[:-1])))
            D.append((e[0], e[1] - e[0]))
    if D:
        a = np.array(D)
        print("n %d  t_shrink median %.3f (10-90%% %.3f..%.3f, min %.3f, max %.3f)   fall median %.3f (10-90%% %.3f..%.3f)" % (
            len(a), np.median(a[:, 0]), np.percentile(a[:, 0], 10), np.percentile(a[:, 0], 90), a[:, 0].min(), a[:, 0].max(),
            np.median(a[:, 1]), np.percentile(a[:, 1], 10), np.percentile(a[:, 1], 90)))


SHRINK_LAG = 0.13     # disc shrink start - slot step, median of 12 clean shots of matches A and B (range 0.08..0.16)


def slot_luma(im):
    """ShotWatcher.readSlot2: mean luma of the "수비" button slot (block slot + (-0.09 W, -0.21 H), +-0.03 W)."""
    h, w = im.shape[:2]; cx = int((0.889 - 0.09) * w); cy = int((0.835 - 0.21) * h); rr = int(0.03 * w)
    c = im[cy - rr:cy + rr + 1:6, cx - rr:cx + rr + 1:6].astype(np.int32)
    return float(((c[..., 2] * 77 + c[..., 1] * 151 + c[..., 0] * 28) >> 8).mean())


def steps(T, L):
    """The 수비 -> 박스아웃 steps of a luma series (HANDOFF 0-4f: 107 -> 116 at the release): the median of the next
    6 frames is 6.5..14 above the median of the last 6, from a level of 95..113, and it stays up for 0.3 s."""
    out = []; i = 6; n = len(L)
    while i < n - 6:
        lo = float(np.median(L[i - 6:i])); d = float(np.median(L[i:i + 6])) - lo
        if 6.5 <= d <= 14 and 95 <= lo <= 113:
            j = i
            while j < n - 6 and float(np.median(L[j + 1:j + 7])) - float(np.median(L[j - 5:j + 1])) > d: j += 1; d = float(np.median(L[j:j + 6])) - float(np.median(L[j - 6:j]))
            lo = float(np.median(L[j - 6:j])); after = L[(T > T[j] + 0.1) & (T <= T[j] + 0.4)]
            if len(after) >= 5 and float(np.median(after)) - lo >= 6 and d <= 14 and 95 <= lo <= 113:
                c = j - 6
                while c < j + 6 and L[c] < lo + d / 2: c += 1
                out.append((float(T[c]), d)); i = j + 12; continue
        i += 1
    return out


def key(mp4, clips, rel, outp):
    """mrel.py -key <mp4> <clips.csv> <rel.csv> <out.key.csv>: the release time of every shot from two clocks, the slot
    step (the game's own, within about 0.04 s of the eye on 3 shots) and the disc shrink (- SHRINK_LAG).
    csv: name, k, ref_s, release_s (video time), release_rel (to ref), src (both = step confirmed by a shrink within
    0.12 s of step + lag / step / shrink / none), t_shrink, step_d, kind (jump = ball in the air >= 0.8 s before the
    rebound label, rim = shorter: dunk, lay-up, tip), x, y, R (disc at the shrink, full-resolution px)."""
    S = {}
    for r in csv.DictReader(open(rel, encoding="utf-8")):
        S.setdefault(int(r["k"]), [])
        if float(r["r"]) > 0: S[int(r["k"])].append((float(r["t_rel"]), float(r["r"]), float(r["ring"]), float(r["x"]), float(r["y"])))
    name = os.path.basename(clips).split(".")[0]; v = cv2.VideoCapture(mp4)
    with open(outp, "w", newline="", encoding="utf-8") as f:
        wr = csv.writer(f); wr.writerow(["name", "k", "ref_s", "release_s", "release_rel", "src", "t_shrink", "step_d", "kind", "x", "y", "R"])
        for row in csv.DictReader(open(clips, encoding="utf-8")):
            k = int(row["k"]); ls = float(row["label_s"]); ref = ls if ls > 0 else float(row["shot_s"])
            v.set(cv2.CAP_PROP_POS_MSEC, max(0.0, ref - 3.2 - 1.0) * 1000); T = []; L = []
            while v.grab():
                t = v.get(cv2.CAP_PROP_POS_MSEC) / 1000.0 - ref
                if t < -3.2: continue
                if t > 0.45: break
                ok, im = v.retrieve()
                if not ok: break
                T.append(t); L.append(slot_luma(im))
            T = np.array(T); L = np.array(L)
            St = [s for s in steps(T, L) if s[0] <= -0.05]; E = events(S.get(k, []))
            rel_t = None; src = "none"; e = None; d = 0.0
            # q[6] = the gap after the last full frame: the fall may have begun up to that much later
            pairs = [(abs(s[0] + SHRINK_LAG - q[0]), s, q) for s in St for q in E if -0.12 <= s[0] + SHRINK_LAG - q[0] <= 0.12 + q[6]]
            # One clock only: it must put the release 0.2..1.65 s before the rebound label (all 24 two-clock shots are
            # inside 0.33..1.48); a shrink further back is a pass, a fall shorter than 0.04 s is the disc being hidden.
            Eg = [q for q in E if -1.65 <= q[0] - SHRINK_LAG <= -0.2 and q[1] - q[0] >= 0.04]; Sg = [s for s in St if -1.65 <= s[0] <= -0.2]
            if pairs: _, s, e = max(pairs, key=lambda p: p[1][0]); rel_t = s[0]; d = s[1]; src = "both"
            elif Eg: e = Eg[-1]; rel_t = e[0] - SHRINK_LAG; src = "shrink"
            elif Sg: rel_t, d = Sg[-1]; src = "step"
            elif E: e = E[-1]; rel_t = e[0] - SHRINK_LAG; src = "shrink?"
            elif St: rel_t, d = St[-1]; src = "step?"
            kind = "" if rel_t is None else ("jump" if -rel_t >= 0.8 else "rim")
            wr.writerow([name, k, "%.3f" % ref, "%.3f" % (ref + rel_t) if rel_t is not None else "", "%.3f" % rel_t if rel_t is not None else "", src,
                         "%.3f" % e[0] if e else "", "%.1f" % d, kind, "%.0f" % e[4] if e else "", "%.0f" % e[5] if e else "", "%.1f" % e[2] if e else ""])
            print("%s%-3d %-6s release %s  shrink %s  steps %s  shrinks %s" % (name, k, src, "%+.3f" % rel_t if rel_t is not None else "-", "%+.3f" % e[0] if e else "-",
                  " ".join("%+.2f(%.0f)" % s for s in St), " ".join("%+.2f" % q[0] for q in E))); sys.stdout.flush()


def main():
    if sys.argv[1] == "-sum": summary(sys.argv[2:]); return
    if sys.argv[1] == "-key": key(*sys.argv[2:6]); return
    mp4, clips, outp = sys.argv[1:4]
    B = 3.0; A = 0.0; stripDir = None; stripK = set(); S0 = 1.95; S1 = 0.95; only = None
    a = sys.argv[4:]
    while a:
        if a[0] == "-from": B = float(a[1]); a = a[2:]
        elif a[0] == "-to": A = float(a[1]); a = a[2:]
        elif a[0] == "-swin": S0 = float(a[1]); S1 = float(a[2]); a = a[3:]
        elif a[0] == "-only": only = set(int(s) for s in a[1].split(",")); a = a[2:]
        elif a[0] == "-strip": stripDir = a[1]; stripK = set(int(s) for s in a[2].split(",")); a = a[3:]
        else: raise SystemExit("unknown " + a[0])
    rows = list(csv.DictReader(open(clips, encoding="utf-8")))
    v = cv2.VideoCapture(mp4)
    with open(outp, "w", newline="", encoding="utf-8") as f:
        wr = csv.writer(f); wr.writerow(["k", "t_rel", "x", "y", "r", "ring", "n"])
        for row in rows:
            k = int(row["k"]); ls = float(row["label_s"]); ref = ls if ls > 0 else float(row["shot_s"])
            if only is not None and k not in only: continue
            rx = float(row["ring_x"]) * 2; ry = float(row["ring_y"]) * 2
            v.set(cv2.CAP_PROP_POS_MSEC, max(0.0, ref - B - 1.0) * 1000)
            F = []; x0 = y0 = 0; tiles = []
            while v.grab():
                t = v.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
                if t < ref - B: continue
                if t > ref - A: break
                ok, im = v.retrieve()
                if not ok: break
                # The whole screen: the ring of the clips csv is the ball handler 0.3 s before the label, i.e. after the
                # shot, and that is often the rebounder (3 of 11 shots of match A were missed with a window around it).
                hh, ww = im.shape[:2]
                cs = [q for q in cands(im) if not hud(q[0] / ww, q[1] / hh)]
                F.append((t - ref, cs))
                if stripDir and k in stripK:       # kept as jpeg: 200 half-size frames raw would be 400 MB
                    tiles.append((t - ref, cv2.imencode(".jpg", cv2.resize(im, None, fx=0.5, fy=0.5, interpolation=cv2.INTER_AREA), [cv2.IMWRITE_JPEG_QUALITY, 88])[1]))
            # There is one ball disc at a time. Start on a ringed full-size disc, follow the nearest candidate (the
            # shrinking disc has no ring), give up after 10 lost frames; when the followed thing has had no ring for
            # 6 frames and a ringed disc is on screen, go to that one (pass, rebound, or the tracker was on junk).
            track = {}; q = None; lost = 0; plain = 0
            for i, (t, cs) in enumerate(F):
                ringed = [p for p in cs if p[3] >= 0.45 and p[2] >= 13]
                if q is not None:
                    near = [p for p in cs if math.hypot(p[0] - q[0], p[1] - q[1]) <= 70 + 1.2 * q[2]]
                    if near:
                        q = min(near, key=lambda p: math.hypot(p[0] - q[0], p[1] - q[1])); lost = 0
                        plain = 0 if q[3] >= 0.3 else plain + 1
                        if plain >= 6 and ringed: q = max(ringed, key=lambda p: p[3] * p[2]); plain = 0
                        track[i] = q; continue
                    lost += 1
                    if lost < 10 and not ringed: continue
                    q = None
                if ringed: q = max(ringed, key=lambda p: p[3] * p[2]); track[i] = q; lost = 0; plain = 0
            for i, (t, cs) in enumerate(F):
                q = track.get(i)
                if q: wr.writerow([k, "%.3f" % t, "%.0f" % q[0], "%.0f" % q[1], "%.1f" % q[2], "%.2f" % q[3], len(cs)])
                else: wr.writerow([k, "%.3f" % t, -1, -1, 0, 0, len(cs)])
            print(k, "frames", len(F), "tracked", len(track)); sys.stdout.flush()
            if tiles:
                T = []
                for i, (t, zj) in enumerate(tiles):
                    if i % 2 or not -S0 <= t <= -S1: continue
                    z = cv2.imdecode(zj, cv2.IMREAD_COLOR)
                    q = track.get(i)
                    if q: cv2.circle(z, (int(q[0] / 2), int(q[1] / 2)), int(q[2] / 2 + 6), (0, 255, 0), 1)
                    cxs = [p[0] / 2 for p in track.values()]; cys = [p[1] / 2 for p in track.values()]
                    mx = int(np.median(cxs)) if cxs else int(rx / 2); my = int(np.median(cys)) if cys else int(ry / 2) - 150
                    xa = max(0, min(z.shape[1] - 240, mx - 120)); ya = max(0, min(z.shape[0] - 300, my - 90))
                    z = z[ya:ya + 300, xa:xa + 240].copy()
                    cv2.putText(z, "%+.2f" % t, (4, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 255, 255), 2); T.append(z)
                hh = min(z.shape[0] for z in T); ww = min(z.shape[1] for z in T); T = [z[:hh, :ww] for z in T]
                while len(T) % 10: T.append(np.zeros_like(T[0]))
                os.makedirs(stripDir, exist_ok=True)
                cv2.imwrite(os.path.join(stripDir, "%s_%02d.jpg" % (os.path.splitext(os.path.basename(clips))[0].split(".")[0], k)),
                            np.vstack([np.hstack(T[j:j + 10]) for j in range(0, len(T), 10)]), [cv2.IMWRITE_JPEG_QUALITY, 80])


if __name__ == "__main__":
    main()
