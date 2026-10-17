#!/usr/bin/env python3
"""Code TOTP courant (RFC 6238, SHA-1, 6 chiffres, 30 s) d'une clé Base32 : remplace l'application d'authentification pendant
l'essai.   python3 tools/e2e/totp.py <clé>   (sans argument : la clé de l'administrateur de l'essai)"""
import base64, hmac, hashlib, struct, sys, time, pathlib

def code(secret: str, at: float | None = None) -> str:
    key = base64.b32decode(secret.replace(" ", "").upper() + "=" * (-len(secret.replace(" ", "")) % 8))
    counter = int((at if at is not None else time.time()) // 30)
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    value = (struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1_000_000
    return f"{value:06d}"

if __name__ == "__main__":
    secret = sys.argv[1] if len(sys.argv) > 1 else (pathlib.Path(__file__).resolve().parents[2] / ".local/e2e/admin.secret").read_text().strip()
    print(code(secret))
