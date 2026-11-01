import re, sys, collections
# Main-thread stalls of the BitChord process in a ui-tour logcat, attributed to
# the last "nav:" screen logged before each one.
for path in sys.argv[1:]:
    lines = open(path, encoding='utf-8', errors='replace').read().splitlines()
    pids = set(re.findall(r' (\d+) +\d+ I BitChord: lifecycle: process start', '\n'.join(lines)))
    nav = 'start'
    per = collections.defaultdict(lambda: [0, 0, 0])  # skipped frames, davey count>=700ms, max davey
    for l in lines:
        m = re.match(r'\S+ \S+ +(\d+) +\d+ ', l)
        if not m or m.group(1) not in pids:
            continue
        n = re.search(r'BitChord: nav: (.+)$', l)
        if n:
            nav = n.group(1).strip()[:40]
        s = re.search(r'Skipped (\d+) frames', l)
        if s:
            per[nav][0] += int(s.group(1))
        d = re.search(r'Davey! duration=(\d+)ms', l)
        if d and int(d.group(1)) >= 700:
            per[nav][1] += 1
            per[nav][2] = max(per[nav][2], int(d.group(1)))
    tot = sum(v[0] for v in per.values())
    print('==', path.split('\\')[-3], 'total skipped frames', tot)
    for k, v in sorted(per.items(), key=lambda x: -x[1][0])[:10]:
        print('  %-40s skipped=%5d davey>=700ms=%2d max=%5dms' % (k, v[0], v[1], v[2]))
