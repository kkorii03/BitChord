"""Union-merge Android/Compose strings.xml files.

usage: strings_union.py <primary.xml> <secondary.xml> <out.xml>
Output = primary file, plus every top-level resource (string/plurals/string-array)
whose name exists only in secondary, inserted before </resources>.
"""
import re
import sys

ENTRY = re.compile(
    r'[ \t]*<(string|plurals|string-array)\b[^>]*\bname="([^"]+)"[^>]*?(?:/>|>.*?</\1>)[ \t]*\r?\n?',
    re.S,
)


def entries(text):
    return {m.group(2): m.group(0) for m in ENTRY.finditer(text)}


def main(primary, secondary, out):
    p = open(primary, encoding="utf-8").read()
    s = open(secondary, encoding="utf-8").read()
    have = entries(p)
    extra = [blk if blk.endswith("\n") else blk + "\n"
             for name, blk in entries(s).items() if name not in have]
    idx = p.rfind("</resources>")
    if idx < 0:
        raise SystemExit(f"no </resources> in {primary}")
    merged = p[:idx] + "".join(extra) + p[idx:]
    open(out, "w", encoding="utf-8", newline="").write(merged)
    print(f"{out}: primary={len(have)} added={len(extra)}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
