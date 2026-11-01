"""Override one setting in an exported .bitchord-settings file.

Usage: set_setting.py <file> <key> <value>

Used by ui-tour to compare a run with one switch flipped (e.g. liquid_glass)
against the listener's own settings. Every entry named <key> gets its "value"
replaced; prints how many were changed so the run log shows it took.
"""
import json
import sys


def main():
    path, key, value = sys.argv[1], sys.argv[2], sys.argv[3]
    with open(path, encoding="utf-8-sig") as f:
        data = json.load(f)
    changed = 0

    def walk(node):
        nonlocal changed
        if isinstance(node, dict):
            for k, v in node.items():
                if k == key and isinstance(v, dict) and "value" in v:
                    v["value"] = value
                    changed += 1
                else:
                    walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    walk(data)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print(f"set_setting: {key}={value} changed={changed}")


if __name__ == "__main__":
    main()
