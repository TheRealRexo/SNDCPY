#!/usr/bin/env python3
"""
SNDCPY Windows Client — supports both legacy (v1) and secure (v2) handshakes.

Usage:
    python3 sndcpy_client.py --host 192.168.1.42 [--port 27183]
    python3 sndcpy_client.py --discover                      # listen for LAN beacons
    python3 sndcpy_client.py --host 192.168.1.42 --pair-code 123456

Beacons are sent to UDP port 27184:  SNDCPYv1 ip=<ip> port=<port> name=<name> proto=2 secure=1
"""

import argparse
import socket
import sys
import threading
import time
import base64

DEFAULT_PORT = 27183
BUFFER = 4096

try:
    from cryptography.hazmat.primitives.asymmetric.ec import SECP256R1
    from cryptography.hazmat.primitives.serialization import (
        Encoding, PublicFormat, PrivateFormat, NoEncryption
    )
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.asymmetric.ec import EllipticCurvePublicKey
    CRYPTO_AVAILABLE = True
except ImportError:
    CRYPTO_AVAILABLE = False


def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    """RFC 5869 HKDF-SHA256."""
    n = (length + 31) // 32
    t = b""
    okm = b""
    for i in range(1, n + 1):
        t = hmac_sha256(t, ikm + info + bytes([i]))
        okm += t
    return okm[:length]


def hmac_sha256(key: bytes, data: bytes) -> bytes:
    """RFC 2104 HMAC-SHA256."""
    block_size = 64
    if len(key) > block_size:
        key = hmac_sha256(key, key)
    elif len(key) < block_size:
        key = key + b"\x00" * (block_size - len(key))
    o_key_pad = bytes(b ^ 0x5c for b in key)
    i_key_pad = bytes(b ^ 0x36 for b in key)
    return __import__("hmac").new(o_key_pad, i_key_pad + data, __import__("hashlib").sha256).digest()


def generate_ec_keypair():
    """Generate an ECDH P-256 key pair and return X963-encoded public key."""
    if not CRYPTO_AVAILABLE:
        raise RuntimeError("cryptography not available")
    from cryptography.hazmat.primitives.asymmetric import ec
    private_key = ec.generate_private_key(SECP256R1())
    public_key = private_key.public_key()
    # Encode as X963 (0x04 || x || y, 65 bytes), base64-encoded
    x = public_key.public_numbers().x.to_bytes(32, 'big')
    y = public_key.public_numbers().y.to_bytes(32, 'big')
    x963 = b'\x04' + x + y
    return base64.b64encode(x963).decode(), private_key


def encode_public_key_b64(public_key_pem: str) -> str:
    """Encode a public key from base64 DER to a second base64 (PROTOCOL-friendly)."""
    der = base64.b64decode(public_key_pem.encode())
    return base64.b64encode(der).decode()


def random_nonce(length: int = 16) -> bytes:
    """Generate random nonce bytes."""
    return __import__("os").urandom(length)


def decode_public_key_b64(encoded: str) -> bytes:
    """Decode the base64 public key to raw DER bytes."""
    return base64.b64decode(encoded)


def decode_bytes_b64(encoded: str) -> bytes:
    """Decode base64 bytes."""
    return base64.b64decode(encoded)


# ── UDP discovery ────────────────────────────────────────────────────────────

BROADCAST_PORT = 27184
BROADCAST_PREFIX = "SNDCPYv1"


def parse_beacon(text: str, src_ip: str):
    """Parse a UDP beacon line into a Discovered dict, or None."""
    if not text.startswith(BROADCAST_PREFIX):
        return None
    ip: str | None = None
    port_val: int | None = None
    name: str | None = None
    for token in text.split(" "):
        eq = token.find("=")
        if eq <= 0:
            continue
        k, v = token[:eq], token[eq + 1:]
        if k == "ip":
            ip = v
        elif k == "port":
            try:
                port_val = int(v)
            except ValueError:
                pass
        elif k == "name":
            name = v
    if ip is None:
        ip = src_ip
    if port_val is None:
        port_val = DEFAULT_PORT
    return {
        "ip": ip,
        "port": port_val,
        "name": name or "SNDCPY",
    }


def discover_servers(timeout: float = 5.0, iface: str | None = None):
    """Listen for UDP beacons on the LAN and return discovered servers."""
    discovered: dict[str, dict] = {}  # ip -> info, dedup by IP

    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        if iface:
            try:
                sock.bind((iface, BROADCAST_PORT))
            except OSError:
                sock.bind(("", BROADCAST_PORT))
        else:
            sock.bind(("", BROADCAST_PORT))
        sock.settimeout(timeout)
        end_time = time.time() + timeout

        while time.time() < end_time:
            try:
                data, addr = sock.recvfrom(512)
                text = data.decode(errors="replace")
                parsed = parse_beacon(text, addr[0])
                if parsed and parsed["ip"] not in discovered:
                    discovered[parsed["ip"]] = parsed
            except socket.timeout:
                break

    except OSError:
        pass

    return list(discovered.values())


# ── Handshake helpers ────────────────────────────────────────────────────────

def build_hello(client_public_b64: str, client_nonce_b64: str) -> bytes:
    """Build a v2 handshake: HELLO SNDCPY <clientPublicKeyB64> <clientNonceB64>\n"""
    return f"HELLO SNDCPY {client_public_b64} {client_nonce_b64}\n".encode()


def parse_handshake_response(text: str):
    """Parse server handshake response.
    Returns (status, server_public_b64, server_nonce_b64) or None."""
    tokens = text.strip().split(" ")
    if len(tokens) < 4 or tokens[0] != "SECURE" and tokens[0] != "PAIRING_REQUIRED" and tokens[0] != "ERROR":
        return None
    status = tokens[0]
    server_public = tokens[1]
    server_nonce = tokens[2]
    return status, server_public, server_nonce


# ── Main client ──────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="SNDCPY Client")
    parser.add_argument("--host", required=True, help="Server IP")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Server port (default 27183)")
    parser.add_argument("--pair-code", help="6-digit pairing code (first connect)")
    parser.add_argument("--discover", action="store_true", help="Discover servers via LAN beacon")
    args = parser.parse_args()

    # ── Discovery mode ──
    if args.discover:
        print("[discovery] listening for SNDCPY LAN beacons on UDP port 27184…")
        servers = discover_servers(timeout=5.0)
        if servers:
            for s in servers:
                print(f"  Found: {s['name']} @ {s['ip']}:{s['port']}")
        else:
            print("  (no servers found — ensure the server is running and broadcasting)")
        return

    # ── Connect mode ──
    print(f"[connecting] {args.host}:{args.port} ...")

    # Generate client ECDH key pair + nonce if we're doing v2
    client_public_b64 = ""
    client_nonce_b64 = ""
    if CRYPTO_AVAILABLE:
        try:
            client_public_b64, client_private = generate_ec_keypair()
            client_nonce_b64 = base64.b64encode(random_nonce()).decode()
        except Exception as e:
            print(f"[error] failed to generate ECDH key: {e}")
            client_public_b64 = ""
            client_nonce_b64 = ""
    else:
        # Legacy fallback: use a hardcoded zero key; server may reject
        client_public_b64 = "BAAAAAEAAAAwaW1hZ2UvdXL/ng=="
        client_nonce_b64 = "AAAAAA=="

    sock = socket.create_connection((args.host, args.port), timeout=8)
    print("[connected]")

    # ── v2 handshake ──
    hello = build_hello(client_public_b64, client_nonce_b64)
    sock.sendall(hello)
    print(">> HELLO SNDCPY <pubkey> <nonce>")

    # Read response
    buf = b""
    deadline = time.time() + 5.0
    while time.time() < deadline:
        try:
            chunk = sock.recv(4096)
            if not chunk:
                break
            buf += chunk
            if b"\n" in buf:
                break
        except (ConnectionResetError, OSError):
            break
    response = buf.decode(errors="replace").strip()
    print(f"<< {response}")

    parsed = parse_handshake_response(response)
    if parsed is None:
        print("[error] unexpected handshake response")
        sock.close()
        return

    status, server_public_b64, server_nonce_b64 = parsed

    # ── Derive session key ──
    if CRYPTO_AVAILABLE and status == "SECURE":
        try:
            server_public_der = decode_bytes_b64(server_public_b64)
            server_key = ECPublicKey.from_encoded_point(SECP256R1(), server_public_der)
            client_key = client_private.public_key()
            # ECDH shared secret
            agreement = client_key.exchange(ec.ECDH(), server_key)
            hkdf = HKDF(algorithm=hashes.SHA256(), length=32, salt=decode_bytes_b64(server_nonce_b64) + decode_bytes_b64(client_nonce_b64), info=b"SNDCPY-SESSION-KEY-v1")
            session_key = hkdf.derive(agreement)
            print("[secure] session key derived (ECDH + HKDF-SHA256)")
        except Exception as e:
            print(f"[error] key derivation failed: {e}")
            session_key = None
    else:
        session_key = None
        print("[legacy] no session key (plain-text mode or crypto unavailable)")

    # ── Pairing ──
    if status == "PAIRING_REQUIRED" and args.pair_code:
        sock.sendall(f"PAIR {args.pair_code}\n".encode())
        print(f">> PAIR {args.pair_code}")
    elif status == "PAIRING_REQUIRED":
        print("Server requires pairing. Use --pair-code <6-digit>.")
        # Simple interactive loop for demo:
        try:
            code = input("Enter pairing code: ").strip()
            sock.sendall(f"PAIR {code}\n".encode())
            print(f">> PAIR {code}")
        except (KeyboardInterrupt, EOFError):
            pass

    # ── Command loop ──
    print("Commands: HOME | BACK | RECENTS | PING | TEXT:<msg>")
    print("Type and press enter (Ctrl+C to quit).")

    try:
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            if line.startswith("TEXT:"):
                msg = line[5:]
                payload = msg.encode("utf-8")
                # Build envelope: [1-byte channel][8-byte ptsUs][payload]
                import struct
                pts_us = int(time.time() * 1_000_000)
                frame = struct.pack("bq", 0x01, pts_us) + payload
                if session_key:
                    # AES-GCM encrypt
                    iv = __import__("os").urandom(12)
                    aesgcm = AESGCM(session_key)
                    ciphertext = aesgcm.encrypt(iv, frame, None)
                    sock.sendall(iv + ciphertext)
                else:
                    # Legacy: send as UTF-8 line
                    sock.sendall((frame.decode("latin-1", errors="replace") + "\n").encode())
                print(f">> TEXT:{msg}")
            elif line in ("HOME", "BACK", "RECENTS", "PING"):
                sock.sendall((line + "\n").encode())
                print(f">> {line}")
            else:
                # Treat as raw command
                sock.sendall((line + "\n").encode())
                print(f">> {line}")
    except KeyboardInterrupt:
        pass
    finally:
        try:
            sock.close()
        except Exception:
            pass
        print("\n[bye]")


if __name__ == "__main__":
    main()