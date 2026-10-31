"""Points the imported settings' proxy at another port of the same host.

Usage: set_proxy_port.py <settings file> <port>
Leaves the file alone when it carries no proxy.
"""
import json
import sys

path, port = sys.argv[1], int(sys.argv[2])
with open(path, encoding="utf-8") as f:
    data = json.load(f)
proxy = data.get("proxy")
if isinstance(proxy, dict) and proxy.get("host"):
    proxy["port"] = port
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print(f"smoke: proxy port -> {port}")
else:
    print("smoke: settings carry no proxy")
