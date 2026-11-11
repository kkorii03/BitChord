"""Fixtures for core/src/test/.../MatchupTest.kt: the Python answers the Kotlin port (core/Matchup.kt) must reproduce.

mfixture.py frames <out folder> <mp4> <name> <t1,t2,...> [<mp4> <name> <t,...> ...]
    writes <name>_<t>.png (1170 x 540) and appends to frames.txt, one line per picture:
    file|me_x me_y me_conf|CLS:x:y:grey;...|x:y:area:rim;...        (mfollow.find_tags/body_grey/find_me, mscan.find_fans)
    The answers are computed on the PNG as read back, so both sides see the same pixels.
mfixture.py picks <out csv> <mfollow.csv> <t0> <t1>
    a slice of an mfollow csv (only the columns the picker reads) with mpick.py's answer for every scan appended:
    t_s,me_x,me_y,me_conf,tags,fans,btn_label,class,tgt_x,tgt_y,my_x,my_y   (mpick -fantgt -autome, started at t0)
"""
import sys, os, csv, subprocess
import numpy as np, cv2
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)

a = sys.argv[1:]
if a[0] == "frames":
    import mfollow, mscan
    out = a[1]; os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "frames.txt"), "a", newline="\n") as f:
        for k in range(2, len(a), 3):
            for t in a[k + 2].split(","):
                im = mscan.at(a[k], float(t)); fn = "%s_%s.png" % (a[k + 1], t.replace(".", "_"))
                cv2.imwrite(os.path.join(out, fn), im, [cv2.IMWRITE_PNG_COMPRESSION, 9]); im = cv2.imread(os.path.join(out, fn))
                g = im[::mfollow.S, ::mfollow.S, ::-1]; me = mfollow.find_me(g)
                tg = ";".join("%s:%.3f:%.3f:%.4f" % (n, x, y, mfollow.body_grey(g, x, y)) for n, x, y in mfollow.find_tags(g))
                fans = []
                for q in mscan.find_fans(im)[:6]:
                    # find_fans rounds the rim to 2 places; recompute nothing, the test allows 0.006
                    fans.append("%.3f:%.3f:%d:%.2f" % (q[1], q[2], q[3], q[4]))
                f.write("%s|%s|%s|%s\n" % (fn, "%.1f %.1f %.4f" % (me[1], me[2], me[0]) if me else "-1 -1 0", tg, ";".join(fans)))
                print(fn, os.path.getsize(os.path.join(out, fn)) // 1024, "KB")
else:
    out, src, t0, t1 = a[1], a[2], float(a[3]), float(a[4])
    rows = [r for r in csv.DictReader(open(src)) if t0 <= float(r["t_s"]) <= t1]
    tmp = out + ".in.csv"; pk = out + ".pk.csv"
    with open(tmp, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)
    subprocess.check_call([sys.executable, "-I", os.path.join(HERE, "mpick.py"), tmp, "XX", "-fantgt", "-autome", "-out", pk], stdout=subprocess.DEVNULL)
    P = list(csv.DictReader(open(pk))); assert len(P) == len(rows)
    with open(out, "w", newline="\n") as f:
        f.write("t_s,me_x,me_y,me_conf,tags,fans,btn_label,class,tgt_x,tgt_y,my_x,my_y\n")
        for r, p in zip(rows, P):
            tg = ";".join(":".join(q.split(":")[:4]) for q in r["tags"].split(";") if q)
            f.write(",".join([r["t_s"], r["me_x"], r["me_y"], r["me_conf"], tg, r["fans"], r["btn_label"], p["class"], p["tgt_x"], p["tgt_y"], p["my_x"], p["my_y"]]) + "\n")
    os.remove(tmp); os.remove(pk)
    print("%d scans, %d with a target, %d with my position" % (len(P), sum(float(p["tgt_x"]) >= 0 for p in P), sum(float(p["my_x"]) >= 0 for p in P)))
