"""Match bundle: gap / marker / ring trajectory in the last 600 ms before each release. mtraj.py <game folder> <release s,...>"""
import sys, csv
g = sys.argv[1]; rel = [float(x) for x in sys.argv[2].split(",")]
R = [r for r in csv.DictReader(open(g + r"\shot.csv"))]; T = [float(r["t_s"]) for r in R]
for s in rel:
    print("release %.2f" % s)
    for off in range(-600, 101, 50):
        i = min(range(len(T)), key=lambda k: abs(T[k] - (s + off / 1000.0))); r = R[i]
        print("   %+5d | disc %s x %s y %s ring_y %s gap %s" % (off, r["disc"], r["x"], r["y"], r["ring_y"], ("%.3f" % (float(r["ring_y"]) - float(r["y"]))) if float(r["ring_y"]) > 0 and float(r["y"]) > 0 else "-"))
