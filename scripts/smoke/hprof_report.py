#!/usr/bin/env python3
"""What fills the Java heap, from an HPROF dump (11-02 smoke leak hunt).

The emulator's retained heap grows ~7 MB a minute until playback fails with
OutOfMemoryError. The raw dump holds every string the app had (API keys among
them) and must never be uploaded, so the runner turns it into this report:
class names, field names and byte counts only, no object contents.

Prints a class histogram, then follows the biggest arrays back through their
referrers (one file pass per level, collections skipped through) until an app
class owns them, grouped by that chain.

Reads Android dumps as written (Android-only records included) and standard
JVM dumps; the id size comes from the header.
Usage: hprof_report.py dump.hprof [--min-array 32768] [--levels 8]
"""
import argparse
import struct
import sys
from collections import Counter, defaultdict

TYPE_SIZE = {2: 0, 4: 1, 5: 2, 6: 4, 7: 8, 8: 1, 9: 2, 10: 4, 11: 8}
PRIM_NAME = {4: "boolean[]", 5: "char[]", 6: "float[]", 7: "double[]", 8: "byte[]",
             9: "short[]", 10: "int[]", 11: "long[]"}
# Holders that only say "something keeps a collection": walk past them.
PASS_THROUGH = ("java.", "kotlin.", "kotlinx.", "android.util.", "androidx.collection.",
                "[L", "Object[]", "dalvik.", "libcore.", "sun.", "jdk.")


class Dump:
    def __init__(self, path):
        self.path = path
        with open(path, "rb") as f:
            head = f.read(64)
        nul = head.index(b"\0")
        self.id_size = struct.unpack(">I", head[nul + 1:nul + 5])[0]
        self.start = nul + 1 + 4 + 8
        self.idfmt = ">I" if self.id_size == 4 else ">Q"
        TYPE_SIZE[2] = self.id_size
        self.strings = {}
        self.class_name = {}      # class object id -> name
        self.class_super = {}
        self.class_fields = {}    # class id -> [(name id, type)]
        self.class_inst_size = {}

    def rid(self, b, o):
        return struct.unpack_from(self.idfmt, b, o)[0]

    def records(self):
        """Yields (tag, body) for top-level records; heap segments as one body."""
        with open(self.path, "rb") as f:
            f.seek(self.start)
            while True:
                h = f.read(9)
                if len(h) < 9:
                    return
                tag, _, length = struct.unpack(">BII", h)
                yield tag, f.read(length)

    def sub_records(self, body):
        """Yields (subtag, offset, end) inside one heap dump segment."""
        n, I = len(body), self.id_size
        o = 0
        while o < n:
            t = body[o]
            o += 1
            s = o
            if t == 0xFF or t in (0x05, 0x07, 0x89, 0x8A, 0x8B, 0x8D, 0x90):
                o += I
            elif t == 0x01:
                o += 2 * I
            elif t in (0x02, 0x03, 0x8E):
                o += I + 8
            elif t in (0x04, 0x06, 0x8C):
                o += I + (4 if t != 0x8C else 0)
            elif t == 0x08:
                o += I + 8
            elif t == 0xFE:
                o += 4 + I
            elif t == 0x20:
                o += I + 4 + 6 * I + 4
                (cp,) = struct.unpack_from(">H", body, o); o += 2
                for _ in range(cp):
                    ty = body[o + 2]; o += 3 + TYPE_SIZE[ty]
                (st,) = struct.unpack_from(">H", body, o); o += 2
                for _ in range(st):
                    ty = body[o + I]; o += I + 1 + TYPE_SIZE[ty]
                (fc,) = struct.unpack_from(">H", body, o); o += 2
                o += fc * (I + 1)
            elif t == 0x21:
                (nb,) = struct.unpack_from(">I", body, o + 2 * I + 4)
                o += 2 * I + 8 + nb
            elif t == 0x22:
                (cnt,) = struct.unpack_from(">I", body, o + I + 4)
                o += I + 8 + I + cnt * I
            elif t == 0x23:
                cnt, ty = struct.unpack_from(">IB", body, o + I + 4)
                o += I + 9 + cnt * TYPE_SIZE[ty]
            elif t == 0xC3:
                o += I + 9
            else:
                raise ValueError(f"unknown heap sub-record 0x{t:02x} at {o - 1}")
            yield t, s, o

    def read_classes(self):
        I = self.id_size
        load = {}
        for tag, body in self.records():
            if tag == 0x01:
                self.strings[self.rid(body, 0)] = body[I:].decode("utf-8", "replace")
            elif tag == 0x02:
                load[self.rid(body, 4)] = self.rid(body, 8 + I)
            elif tag in (0x0C, 0x1C):
                for t, s, e in self.sub_records(body):
                    if t != 0x20:
                        continue
                    cid = self.rid(body, s)
                    self.class_super[cid] = self.rid(body, s + I + 4)
                    o = s + I + 4 + 6 * I
                    (self.class_inst_size[cid],) = struct.unpack_from(">I", body, o); o += 4
                    (cp,) = struct.unpack_from(">H", body, o); o += 2
                    for _ in range(cp):
                        ty = body[o + 2]; o += 3 + TYPE_SIZE[ty]
                    (st,) = struct.unpack_from(">H", body, o); o += 2
                    for _ in range(st):
                        ty = body[o + I]; o += I + 1 + TYPE_SIZE[ty]
                    (fc,) = struct.unpack_from(">H", body, o); o += 2
                    fields = []
                    for _ in range(fc):
                        fields.append((self.rid(body, o), body[o + I])); o += I + 1
                    self.class_fields[cid] = fields
        for cid, sid in load.items():
            self.class_name[cid] = self.strings.get(sid, f"?{cid:x}").replace("/", ".")
        # Field layout of an instance: its own class's fields, then each super's.
        self.layout = {}
        for cid in self.class_fields:
            out, c = [], cid
            while c and c in self.class_fields:
                out.extend(self.class_fields[c]); c = self.class_super.get(c)
            self.layout[cid] = out

    def name(self, cid):
        return self.class_name.get(cid, f"?{cid:x}")


def scan(d, want_big, min_array):
    """Pass 1: histogram + big arrays (id -> (class name, bytes))."""
    I = d.id_size
    hist = Counter(); count = Counter(); big = {}
    for tag, body in d.records():
        if tag not in (0x0C, 0x1C):
            continue
        for t, s, e in d.sub_records(body):
            if t == 0x21:
                cls = d.name(d.rid(body, s + I + 4)); size = e - s - (2 * I + 8)
            elif t == 0x22:
                cls = d.name(d.rid(body, s + I + 8)); size = e - s - (2 * I + 8)
            elif t == 0x23:
                (cnt, ty) = struct.unpack_from(">IB", body, s + I + 4)
                cls = PRIM_NAME[ty]; size = cnt * TYPE_SIZE[ty]
            else:
                continue
            hist[cls] += size; count[cls] += 1
            if want_big and t in (0x22, 0x23) and size >= min_array:
                big[d.rid(body, s)] = (cls, size)
    return hist, count, big


def referrers(d, targets):
    """One pass: target id -> (holder id, holder class, field name)."""
    I = d.id_size
    out = {}
    for tag, body in d.records():
        if tag not in (0x0C, 0x1C):
            continue
        for t, s, e in d.sub_records(body):
            if t == 0x21:
                oid = d.rid(body, s); cid = d.rid(body, s + I + 4)
                o = s + 2 * I + 8
                for fname, ty in d.layout.get(cid, ()):
                    if ty == 2:
                        ref = d.rid(body, o)
                        if ref in targets and ref not in out:
                            out[ref] = (oid, d.name(cid), d.strings.get(fname, "?"))
                    o += TYPE_SIZE[ty]
            elif t == 0x22:
                oid = d.rid(body, s); cls = d.name(d.rid(body, s + I + 8))
                (cnt,) = struct.unpack_from(">I", body, s + I + 4)
                o = s + 2 * I + 8
                for i in range(cnt):
                    ref = d.rid(body, o + i * I)
                    if ref in targets and ref not in out:
                        out[ref] = (oid, cls, "[]")
            elif t == 0x20:
                # A static field: the class itself is the holder.
                pass
    return out


def statics(d, targets):
    """Class statics that hold a target directly (static caches)."""
    I = d.id_size
    out = {}
    for tag, body in d.records():
        if tag not in (0x0C, 0x1C):
            continue
        for t, s, e in d.sub_records(body):
            if t != 0x20:
                continue
            cid = d.rid(body, s)
            o = s + I + 4 + 6 * I + 4
            (cp,) = struct.unpack_from(">H", body, o); o += 2
            for _ in range(cp):
                ty = body[o + 2]; o += 3 + TYPE_SIZE[ty]
            (st,) = struct.unpack_from(">H", body, o); o += 2
            for _ in range(st):
                nid = d.rid(body, o); ty = body[o + I]; o += I + 1
                if ty == 2:
                    ref = d.rid(body, o)
                    if ref in targets:
                        out[ref] = (cid, d.name(cid) + " (static)", d.strings.get(nid, "?"))
                o += TYPE_SIZE[ty]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dump")
    ap.add_argument("--min-array", type=int, default=32768)
    ap.add_argument("--levels", type=int, default=8)
    a = ap.parse_args()
    d = Dump(a.dump)
    d.read_classes()
    hist, count, big = scan(d, True, a.min_array)
    total = sum(hist.values())
    print(f"heap objects total {total / 1e6:.1f} MB, classes {len(hist)}")
    print("\n== top classes by shallow size")
    for cls, size in hist.most_common(30):
        print(f"{size / 1e6:8.2f} MB {count[cls]:8d}  {cls}")
    print("\n== app classes by count")
    app = [(c, n) for c, n in count.items() if c.startswith("com.music.")]
    for cls, n in sorted(app, key=lambda x: -x[1])[:25]:
        print(f"{n:8d} {hist[cls] / 1e6:7.2f} MB  {cls}")

    print(f"\n== arrays >= {a.min_array} bytes: {len(big)}, {sum(s for _, s in big.values()) / 1e6:.1f} MB; owner chains")
    # chain[target] = list of "Class.field" from the array upwards
    chain = {t: [cls] for t, (cls, _) in big.items()}
    # current holder id -> the original arrays it leads to (a map's table
    # holds many of them, so one holder carries a list)
    frontier = {t: [t] for t in big}
    for _ in range(a.levels):
        if not frontier:
            break
        found = referrers(d, set(frontier))
        found.update(statics(d, set(frontier) - set(found)))
        nxt = defaultdict(list)
        for cur, origs in frontier.items():
            hit = found.get(cur)
            for orig in origs:
                chain[orig].append(f"{hit[1]}.{hit[2]}" if hit else "<root/unreferenced>")
            if hit and hit[1].startswith(PASS_THROUGH) and "(static)" not in hit[1]:
                nxt[hit[0]].extend(origs)
        frontier = nxt
    groups = defaultdict(lambda: [0, 0])
    for t, (cls, size) in big.items():
        key = " <- ".join(chain[t])
        groups[key][0] += size; groups[key][1] += 1
    for key, (size, n) in sorted(groups.items(), key=lambda x: -x[1][0])[:30]:
        print(f"{size / 1e6:8.2f} MB x{n:<4d} {key}")


if __name__ == "__main__":
    sys.exit(main())
