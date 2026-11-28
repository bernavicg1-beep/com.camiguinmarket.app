#!/usr/bin/env python3
"""Print the SHA-256 hash to paste into admins/{uid}.pinHash.

Usage:  python3 scripts/admin-pin-hash.py 1234
"""
import getpass
import hashlib
import sys

pin = sys.argv[1] if len(sys.argv) > 1 else getpass.getpass("Admin PIN: ")
pin = pin.strip()
if len(pin) < 4 or not pin.isdigit():
    sys.exit("Use at least 4 digits.")
print(hashlib.sha256(pin.encode()).hexdigest())
