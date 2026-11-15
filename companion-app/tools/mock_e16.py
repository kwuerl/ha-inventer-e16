#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Korbinian Würl
"""Stand-in for an Easy Connect e16 controller, for the companion app's tests.

* TLS 1.2 PSK server on TCP 47820 (identity "zirconia", PSK-AES128-CBC-SHA) that answers
  ZoneViewHeader (145) and ZoneViewRow (146) data requests with a table of four zones.
* MDSDP responder on UDP 47818 that answers "Search" broadcasts with an AnnounceV4.

Needs Python 3.13+ (ssl.SSLContext.set_psk_server_callback). Key, device ID and zone names
are made up.

    python3 mock_e16.py                                  # key 0102030405060708
    python3 mock_e16.py --key 0123456789abcdef --port 47820
"""
from __future__ import annotations

import argparse
import socket
import ssl
import struct
import sys
import threading
import uuid

HEADER = 10
DEVICE_ID = b"ABCDE-FGHIJ"
ZONES = [(1, "Zone 1"), (2, "Bedroom"), (3, "Hallway"), (0, "")]  # the fourth zone is unused


def crc8(data: bytes, skip: int = 1) -> int:
    crc = 0
    for b in data[skip:]:
        x = crc ^ b
        for _ in range(8):
            x = (x << 1) & 0xFF
            if x & 0x80:
                x ^= 0x07
        crc = x
    return crc & 0xFF


def build(ptype: int, op: int, payload: bytes = b"", backtrack: bytes = b"") -> bytes:
    buf = bytearray(HEADER + len(payload))
    buf[1] = HEADER + len(payload)
    buf[2] = len(backtrack) // 3
    buf[3] = ptype
    buf[4] = op
    buf[HEADER:] = payload
    buf[0] = crc8(buf, 1)
    return bytes(buf) + backtrack


def zone_row(zone_id: int, name: str) -> bytes:
    row = bytearray(85)
    row[0] = zone_id
    raw = name.encode()[:16]
    row[1:1 + len(raw)] = raw
    row[17] = 2                               # fan speed
    row[18] = 0                               # playback: play
    row[23] = 0b100000 if zone_id else 0      # flags: bit 5 has devices
    row[24] = 2                               # mode in the zone status table: heat recovery
    return bytes(row)


def handle(conn: ssl.SSLSocket) -> None:
    buf = bytearray()
    try:
        while True:
            chunk = conn.recv(512)
            if not chunk:
                return
            buf += chunk
            while len(buf) >= 2 and buf[1] and len(buf) >= buf[1]:
                length = buf[1]
                frame = bytes(buf[:length])
                del buf[:length]
                if crc8(frame) != frame[0]:
                    continue
                ptype, payload = frame[3], frame[HEADER:]
                if ptype == 145:
                    # one backtracking record on purpose: the client must skip it
                    conn.sendall(build(145, 64, struct.pack("<BH", len(ZONES), 5), b"\x00\x01\xc4"))
                elif ptype == 146 and payload:
                    idx = payload[0]
                    if idx < len(ZONES):
                        conn.sendall(build(146, 64, zone_row(*ZONES[idx])))
    except (OSError, ssl.SSLError):
        return
    finally:
        conn.close()


def serve_tls(host: str, port: int, key: bytes) -> None:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ctx.maximum_version = ssl.TLSVersion.TLSv1_2
    ctx.set_ciphers("PSK-AES128-CBC-SHA:@SECLEVEL=0")
    ctx.set_psk_server_callback(lambda identity: key if identity == "zirconia" else b"")
    srv = socket.create_server((host, port), reuse_port=False)
    print(f"[mock-e16] TLS-PSK listening on {host}:{port}", flush=True)
    while True:
        raw, addr = srv.accept()
        try:
            conn = ctx.wrap_socket(raw, server_side=True)
        except (OSError, ssl.SSLError) as err:
            print(f"[mock-e16] handshake from {addr[0]} failed: {err}", flush=True)
            raw.close()
            continue
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


def serve_mdsdp(announce_ip: str, tcp_port: int, udp_port: int, name: str) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", udp_port))
    device = uuid.UUID("00000000-0000-4000-8000-000000000e16")
    service = uuid.UUID("00000000-0000-0000-0000-00000000e16a")
    strings = b"".join(bytes([len(s)]) + s for s in (b"zirconia", DEVICE_ID, name.encode(), b"Home"))
    while True:
        data, addr = sock.recvfrom(512)
        if data[:5] == b"MDSDP" and len(data) >= 6 and data[5] == 0x83:
            reply = bytearray(b"MDSDP") + bytes([0x00]) + struct.pack(">H", 1)
            reply += socket.inet_aton(announce_ip) + struct.pack(">HH", tcp_port, 120)
            reply += device.bytes + service.bytes + strings
            sock.sendto(bytes(reply), addr)


def main() -> int:
    if sys.version_info < (3, 13):
        print("Python 3.13+ is required for TLS-PSK", file=sys.stderr)
        return 2
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--key", default="0102030405060708", help="PSK as 16 hex characters")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=47820)
    ap.add_argument("--mdsdp-port", type=int, default=47818)
    ap.add_argument("--announce-ip", default="0.0.0.0", help="IP to put in MDSDP replies")
    ap.add_argument("--name", default="Easy Connect e16")
    ap.add_argument("--no-mdsdp", action="store_true")
    args = ap.parse_args()
    try:
        key = bytes.fromhex(args.key)
    except ValueError:
        print("--key must be hex", file=sys.stderr)
        return 2
    if not args.no_mdsdp:
        threading.Thread(target=serve_mdsdp, args=(args.announce_ip, args.port, args.mdsdp_port, args.name),
                         daemon=True).start()
    serve_tls(args.host, args.port, key)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
