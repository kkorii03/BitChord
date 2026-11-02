"""Checks that a merge kept every patch from our side.

For every file our side changed since the merge base, each non-trivial line
our side ADDED must still exist in the merge result (in the same file, or
anywhere in the result when the code moved, e.g. into ko/). Lines our side
REMOVED that come back in the same file of the result are reported as
revived. Lost lines are traced to the commit that wrote them (git blame on our
side), so a lost patch shows up by date and subject.

Usage: python -I patch-survival.py --repo <worktree> --ours <ref> --result <ref>
       [--base <ref>] [--since 2026-11-01] [--out report.txt]
"""
import argparse
import collections
import subprocess
import sys

TEXT_EXT = (".kt", ".kts", ".java", ".xml", ".py", ".sh", ".ps1", ".json", ".pro", ".toml", ".gradle")
SKIP_PREFIX = (".github/", ".dsq/", "docs/", "ship/")
TRIVIAL = {"}", "{", ")", "(", "),", "},", "];", "]", "*/", "/**", "*", "//", "else {", "} else {", "return", ""}


def git(repo, *args, binary=False):
    out = subprocess.run(["git", "-C", repo, *args], capture_output=True, check=True)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def norm(line):
    return " ".join(line.strip().split())


def meaningful(line):
    s = norm(line)
    return len(s) >= 12 and s not in TRIVIAL and not s.startswith("import ")


def tree_lines(repo, ref):
    """{path: set(normalized lines)} for text files of [ref]."""
    paths = [p for p in git(repo, "ls-tree", "-r", "--name-only", ref).splitlines()
             if p.endswith(TEXT_EXT) and not p.startswith(SKIP_PREFIX)]
    req = "".join(f"{ref}:{p}\n" for p in paths).encode()
    proc = subprocess.run(["git", "-C", repo, "cat-file", "--batch"], input=req, capture_output=True, check=True)
    data, pos, files = proc.stdout, 0, {}
    for p in paths:
        nl = data.index(b"\n", pos)
        header = data[pos:nl].split()
        size = int(header[2]) if len(header) >= 3 and header[1] != b"missing" else 0
        body = data[nl + 1:nl + 1 + size]
        pos = nl + 1 + size + 1
        files[p] = {norm(l) for l in body.decode("utf-8", "replace").splitlines()}
    return files


def diff_lines(repo, base, ours):
    """{path: (added[list], removed[list])} our side changed since base."""
    out = git(repo, "diff", "--no-color", "--no-renames", "-U0", base, ours)
    result, path = collections.defaultdict(lambda: ([], [])), None
    for line in out.splitlines():
        if line.startswith("+++ "):
            path = line[6:] if line.startswith("+++ b/") else None
        elif line.startswith("--- "):
            continue
        elif path and not path.startswith(SKIP_PREFIX) and path.endswith(TEXT_EXT):
            if line.startswith("+") and meaningful(line[1:]):
                result[path][0].append(line[1:])
            elif line.startswith("-") and meaningful(line[1:]):
                result[path][1].append(line[1:])
    return result


def blame_commits(repo, ours, path, wanted):
    """{normalized line: (sha, date, subject)} for lines of [path] at [ours]."""
    out = git(repo, "blame", "--line-porcelain", ours, "--", path)
    info, found, sha = {}, {}, None
    for line in out.splitlines():
        if line and not line.startswith("\t") and len(line.split()[0]) == 40 and line.split()[0].isalnum():
            sha = line.split()[0]
            info.setdefault(sha, {})
        elif line.startswith("committer-time "):
            info[sha]["time"] = int(line.split()[1])
        elif line.startswith("summary "):
            info[sha]["summary"] = line[8:]
        elif line.startswith("\t"):
            n = norm(line[1:])
            if n in wanted and n not in found:
                found[n] = sha
    return {n: (s, info[s].get("time", 0), info[s].get("summary", "")) for n, s in found.items()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--ours", required=True)
    ap.add_argument("--result", required=True)
    ap.add_argument("--base")
    ap.add_argument("--since", default="")
    ap.add_argument("--out")
    ap.add_argument("--all", action="store_true", help="list every lost line, not 6 per commit")
    a = ap.parse_args()
    base = a.base or git(a.repo, "merge-base", a.ours, a.result).strip()
    if base == git(a.repo, "rev-parse", a.ours).strip():
        # ours is an ancestor of result: compare with the other parent's base instead
        print("note: ours is an ancestor of result; base given explicitly is recommended", file=sys.stderr)
    ours_tree = tree_lines(a.repo, a.ours)
    res_tree = tree_lines(a.repo, a.result)
    everywhere = set().union(*res_tree.values()) if res_tree else set()
    ours_everywhere = set().union(*ours_tree.values()) if ours_tree else set()
    changes = diff_lines(a.repo, base, a.ours)

    lost, moved, revived, checked = collections.defaultdict(list), 0, collections.defaultdict(list), 0
    for path, (added, removed) in changes.items():
        here = res_tree.get(path, set())
        for line in added:
            n = norm(line)
            if n not in ours_tree.get(path, set()):
                continue  # added then removed again on our side
            checked += 1
            if n in here:
                continue
            if n in everywhere:
                moved += 1
            else:
                lost[path].append(n)
        for line in removed:
            n = norm(line)
            if n in ours_everywhere:
                continue
            if n in here:
                revived[path].append(n)

    report = [f"base {base[:8]}  ours {a.ours}  result {a.result}",
              f"checked added lines: {checked}  moved elsewhere: {moved}  LOST: {sum(map(len, lost.values()))}  "
              f"revived removed lines: {sum(map(len, revived.values()))}"]
    by_commit = collections.defaultdict(list)
    for path, lines in lost.items():
        owners = blame_commits(a.repo, a.ours, path, set(lines))
        for n in lines:
            sha, t, subj = owners.get(n, ("?" * 40, 0, "?"))
            by_commit[(t, sha, subj)].append(f"{path}: {n[:140]}")
    import datetime
    for (t, sha, subj), items in sorted(by_commit.items(), reverse=True):
        day = datetime.datetime.fromtimestamp(t).strftime("%m-%d %H:%M") if t else "?"
        if a.since and t and day < a.since[5:]:
            mark = "  "
        else:
            mark = "!!"
        report.append(f"{mark} LOST {len(items):4d}  {day}  {sha[:8]}  {subj[:90]}")
        shown = items if a.all else items[:6]
        report.extend("        " + i for i in shown)
        if len(items) > len(shown):
            report.append(f"        ... {len(items) - len(shown)} more")
    for path, lines in revived.items():
        report.append(f"REVIVED {len(lines):4d}  {path}")
        report.extend("        " + n[:140] for n in lines[:4])
    text = "\n".join(report)
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    sys.stdout.buffer.write((text + "\n").encode("utf-8"))


if __name__ == "__main__":
    main()
