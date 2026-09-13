#!/usr/bin/env python3
"""A TCP pipe that can be made weak on demand: cut, stall, slow, or half-open.

Forwards one port to another byte for byte, so it carries the mux WebSocket and
the unary HTTP calls alike. Four ways to make it misbehave, because "a weak
network" is four different failures and they find different bugs:

    --switch FILE          the link exists only while FILE does: deleting it
                           closes every connection and refuses new ones. A clean
                           cut -- a lift, a tunnel, a network switch.
    --delay-ms N           every write waits N ms (with --jitter M, give or take).
                           A slow link: latency without loss, which is where read
                           timeouts and "is it stuck?" questions live.
    --stall-ms N           stop forwarding for N ms without closing anything: a
      --stall-chance P     black hole. The socket looks alive, nothing arrives,
                           and only keepalives and timeouts can notice. P is the
                           chance per second (default 1.0 = always).
    --half-open            accept and then never read or write. The worst case,
                           and the one a phone on a dying router actually gets.
    --exempt-path SUBSTR   leave connections whose first request line contains
                           SUBSTR alone. Used to model a *slow response* rather
                           than a slow link: the Host closes a WebSocket whose
                           pong is late by more than its two-second heartbeat, so
                           delaying the mux models a broken host instead.

Packet loss itself is not simulated, and cannot be from here: this pipe sits
above TCP, where dropping bytes would desynchronise the stream rather than look
like loss. Losing packets for real needs `tc netem` on an interface, which needs
root; what is simulated instead are the *effects* a lossy link has on the app --
stalls, timeouts, resets, and slow responses.

Used by `tools/live-harness.sh`, so the app is tested against failures that
really happen rather than against mocked ones that prove nothing.
"""
from __future__ import annotations

import argparse
import os
import random
import select
import socket
import threading
import time


def parse(address: str) -> tuple[str, int]:
    host, _, port = address.rpartition(":")
    return host or "0.0.0.0", int(port)


class Link:
    """The proxy's state: whether the link is up, and what is flowing over it."""

    def __init__(self, switch: str | None, delay_ms: int = 0, jitter_ms: int = 0,
                 stall_ms: int = 0, stall_chance: float = 1.0, half_open: bool = False) -> None:
        self.switch = switch
        self.delay_ms = delay_ms
        self.jitter_ms = jitter_ms
        self.stall_ms = stall_ms
        self.stall_chance = stall_chance
        self.half_open = half_open
        self.exempt_path: str | None = None
        self.up = True
        self.stalled_until = 0.0
        self.lock = threading.Lock()
        self.live: list[tuple[socket.socket, socket.socket]] = []

    def _wait_stall(self) -> None:
        while True:
            remaining = self.stalled_until - time.monotonic()
            if remaining <= 0:
                return
            time.sleep(min(remaining, 0.2))

    def pause(self) -> None:
        """Hold one write for the link's latency, and for any stall it runs into.

        A stall does not close anything: the bytes simply stop, which is the state
        a phone on a dying router sits in. Only a keepalive or a timeout can
        notice, so this is the mode that tests them.
        """
        self._wait_stall()
        if self.stall_ms and random.random() < self.stall_chance:
            self.stalled_until = time.monotonic() + self.stall_ms / 1000.0
            print(f"stalling for {self.stall_ms} ms", flush=True)
            self._wait_stall()

    def latency(self) -> float:
        delay = self.delay_ms
        if self.jitter_ms:
            delay = max(0, delay + random.randint(-self.jitter_ms, self.jitter_ms))
        return delay / 1000.0

    def is_up(self) -> bool:
        if self.switch is None:
            return True
        return os.path.exists(self.switch)

    def track(self, pair: tuple[socket.socket, socket.socket]) -> None:
        with self.lock:
            self.live.append(pair)

    def forget(self, pair: tuple[socket.socket, socket.socket]) -> None:
        with self.lock:
            if pair in self.live:
                self.live.remove(pair)

    def cut(self) -> None:
        with self.lock:
            pairs, self.live = self.live, []
        for left, right in pairs:
            for sock in (left, right):
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                sock.close()

    def watch(self) -> None:
        """Close everything the moment the switch says the link is gone."""
        while True:
            time.sleep(0.2)
            now = self.is_up()
            if self.up and not now:
                print("link down", flush=True)
                self.cut()
            elif not self.up and now:
                print("link up", flush=True)
            self.up = now


def describe(sock: socket.socket) -> str:
    """The peer's address, or `?` once the socket is gone."""
    try:
        return str(sock.getpeername())
    except OSError:
        return "?"


def pipe(link: Link | None, pair: tuple[socket.socket, socket.socket], src: socket.socket, dst: socket.socket) -> None:
    """Forward one direction, charging the link's latency once per burst.

    A real slow link has latency *and* bandwidth: one round trip costs the
    latency, while a bulk transfer costs the bandwidth and almost no latency.
    Delaying every write instead throttled everything, which starved the Host's
    two-second WebSocket heartbeat and made a merely slow link look like a broken
    one -- the model was wrong, not the client.
    """
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            if link is not None:
                link.pause()
                wait = link.latency()
                if wait:
                    time.sleep(wait)
            dst.sendall(data)
            # Whatever the peer had already buffered left on the same round trip.
            while select.select([src], [], [], 0.002)[0]:
                more = src.recv(65536)
                if not more:
                    return
                dst.sendall(more)
    except OSError:
        pass
    finally:
        if link is not None:
            link.forget(pair)
        # Closing first, and logging afterwards: a diagnostic that raises must not
        # be able to leave sockets open, which is what an unguarded `getpeername`
        # on a socket the peer had already dropped did -- it killed the forwarding
        # thread and the connection with it.
        for sock in pair:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            sock.close()
        print(f"closing {describe(src)} <- {describe(dst)}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen", default="0.0.0.0:3099")
    parser.add_argument("--target", default="127.0.0.1:3080")
    parser.add_argument("--switch", default=None, help="forward only while this file exists")
    parser.add_argument("--delay-ms", type=int, default=0, help="wait this long before each write")
    parser.add_argument("--jitter-ms", type=int, default=0, help="vary the delay by this much")
    parser.add_argument("--stall-ms", type=int, default=0, help="stop forwarding for this long, without closing")
    parser.add_argument("--stall-chance", type=float, default=0.05, help="chance a write starts a stall (default 5%%)")
    parser.add_argument("--half-open", action="store_true", help="accept and never forward anything")
    parser.add_argument("--exempt-path", default=None, help="forward connections whose first request line contains this untouched")
    args = parser.parse_args()

    listen, target = parse(args.listen), parse(args.target)
    link = Link(args.switch, args.delay_ms, args.jitter_ms, args.stall_ms, args.stall_chance, args.half_open)
    link.exempt_path = args.exempt_path
    link.up = link.is_up()
    threading.Thread(target=link.watch, daemon=True).start()

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(listen)
    server.listen(64)
    print(f"flaky proxy on {listen[0]}:{listen[1]} -> {target[0]}:{target[1]}"
          + (f" (switch {args.switch})" if args.switch else ""), flush=True)

    while True:
        client, address = server.accept()
        if not link.is_up():
            client.close()
            continue
        try:
            upstream = socket.create_connection(target)
        except OSError as error:
            print(f"upstream refused for {address}: {error}", flush=True)
            client.close()
            continue
        exempt = False
        if link.exempt_path:
            if select.select([client], [], [], 0.05)[0]:
                try:
                    head = client.recv(4096, socket.MSG_PEEK).decode("latin-1", "replace")
                except OSError:
                    head = ""
                exempt = link.exempt_path in head
            if exempt:
                print(f"exempt (fast) link for {address}", flush=True)
        if link.half_open:
            # Accepted, connected upstream, and never a byte either way: the
            # socket is alive and nothing will ever arrive.
            print(f"half-open for {address}", flush=True)
            continue
        pair = (client, upstream)
        link.track(pair)
        print(f"link up for {address}{' (exempt)' if exempt else ''}", flush=True)
        fast = None if exempt else link
        threading.Thread(target=pipe, args=(fast, pair, client, upstream), daemon=True).start()
        threading.Thread(target=pipe, args=(fast, pair, upstream, client), daemon=True).start()


if __name__ == "__main__":
    main()
