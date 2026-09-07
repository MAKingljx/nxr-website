#!/usr/bin/env python3
"""A scoped macOS SSH transport that bypasses TUN without changing routes.

Used only as OpenSSH ProxyCommand: SSH itself still performs authentication,
host-key verification and encryption. This helper never listens on a port.
"""

import argparse
import os
import select
import socket
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--interface", required=True)
    parser.add_argument("host")
    parser.add_argument("port", type=int)
    args = parser.parse_args()
    if args.host != "147.182.183.201" or args.port != 22:
        parser.error("this connector is restricted to the verified NXR SSH endpoint")
    if sys.platform != "darwin":
        parser.error("interface-bound SSH currently supports macOS only")
    interface_index = socket.if_nametoindex(args.interface)
    if args.interface.startswith(("utun", "tun", "lo")):
        parser.error("choose the verified physical network interface")

    connection = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        # Darwin IP_BOUND_IF (25) binds routing to a physical interface. Merely
        # unsetting HTTP_PROXY does not bypass transparent TUN interception.
        option = getattr(socket, "IP_BOUND_IF", 25)
        connection.setsockopt(socket.IPPROTO_IP, option, interface_index)
        connection.settimeout(15)
        connection.connect((args.host, args.port))
        if connection.getsockopt(socket.IPPROTO_IP, option) != interface_index:
            raise RuntimeError("socket interface binding was not retained")
        connection.settimeout(None)
        reading_stdin = True
        while True:
            readers = [connection] + ([sys.stdin.fileno()] if reading_stdin else [])
            ready, _, _ = select.select(readers, [], [], 30)
            if connection in ready:
                chunk = connection.recv(65536)
                if not chunk:
                    break
                while chunk:
                    written = os.write(sys.stdout.fileno(), chunk)
                    chunk = chunk[written:]
            if reading_stdin and sys.stdin.fileno() in ready:
                chunk = os.read(sys.stdin.fileno(), 65536)
                if chunk:
                    connection.sendall(chunk)
                else:
                    reading_stdin = False
                    connection.shutdown(socket.SHUT_WR)
    finally:
        connection.close()


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError) as error:
        print(f"NXR direct SSH transport: {error}", file=sys.stderr)
        raise SystemExit(1)
