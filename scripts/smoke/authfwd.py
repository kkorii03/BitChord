"""Unauthenticated proxy on the runner that forwards to an authenticated one.

Android's global http_proxy setting has no place for credentials, so the
emulator points at this (10.0.2.2:3128) and each request is passed upstream
with a Proxy-Authorization header added. Clients connect by host name here,
unlike the emulator's own -http-proxy, which hands the proxy IPv6 literals the
home end cannot reach.

Usage: authfwd.py <upstream host:port>   (credentials in SMOKE_UPSTREAM_AUTH,
base64 of user:password)
"""
import asyncio
import os
import sys

UP_HOST, UP_PORT = sys.argv[1].rsplit(":", 1)
UP_PORT = int(UP_PORT)
AUTH = os.environ.get("SMOKE_UPSTREAM_AUTH", "")


async def pipe(reader, writer):
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            writer.write(data)
            await writer.drain()
    except Exception:
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


async def handle(client_r, client_w):
    try:
        head = await asyncio.wait_for(client_r.readuntil(b"\r\n\r\n"), 30)
        up_r, up_w = await asyncio.wait_for(asyncio.open_connection(UP_HOST, UP_PORT), 20)
    except Exception:
        client_w.close()
        return
    first, _, rest = head.partition(b"\r\n")
    if AUTH:
        head = first + b"\r\nProxy-Authorization: Basic " + AUTH.encode() + b"\r\n" + rest
    up_w.write(head)
    await up_w.drain()
    await asyncio.gather(pipe(client_r, up_w), pipe(up_r, client_w))


async def main():
    server = await asyncio.start_server(handle, "0.0.0.0", 3128)
    print(f"authfwd on :3128 -> {UP_HOST}:{UP_PORT}", flush=True)
    async with server:
        await server.serve_forever()


asyncio.run(main())
