"""Repair the PIN hash checks: the previous script stripped the commas between
check() arguments, so the file no longer parsed. This rewrites that block and
asserts that the result parses before it is committed."""
import ast
import sys

TESTS = "scripts/test-firestore-rules.py"
EXTRAS = "app/src/main/java/com/camiguinmarket/app/Extras.kt"
ADMIN = "app/src/main/java/com/camiguinmarket/app/Admin.kt"

BLOCK = '''# --- admin PIN hash: settable once from the app, then frozen --------------
# The PIN lives on admins/{uid}.pinHash, so a fresh install cannot pick its own.
# The admin may add it once to their own document; after that only the Console
# can change it, and nobody else can touch it.
seed("/admins", "pinsetter", {"fields": {"active": {"booleanValue": True}}})
check("pin: the admin can set their own PIN hash once", "PATCH", "/admins/pinsetter", 200,
      uid="pinsetter", mask="pinHash",
      body={"fields": {"pinHash": {"stringValue": "a" * 64}}})
check("pin: the PIN hash cannot be replaced from the app", "PATCH", "/admins/pinsetter", 403,
      uid="pinsetter", mask="pinHash",
      body={"fields": {"pinHash": {"stringValue": "b" * 64}}})
check("pin: another user cannot set the PIN hash", "PATCH", "/admins/pinsetter", 403,
      uid="u1", mask="pinHash",
      body={"fields": {"pinHash": {"stringValue": "c" * 64}}})
seed("/admins", "pinunset", {"fields": {"active": {"booleanValue": True}}})
check("pin: a non-hash value is rejected", "PATCH", "/admins/pinunset", 403,
      uid="pinunset", mask="pinHash",
      body={"fields": {"pinHash": {"stringValue": "1234"}}})
check("pin: the PIN hash cannot be cleared", "PATCH", "/admins/pinunset", 403,
      uid="pinunset", mask="pinHash",
      body={"fields": {"pinHash": {"nullValue": None}}})
check("pin: setting the hash does not unlock other writes", "PATCH", "/admins/pinunset", 403,
      uid="pinunset", mask="active",
      body={"fields": {"active": {"booleanValue": False}}})

'''

text = open(TESTS, encoding="utf-8").read()
start = text.index("# --- admin PIN hash: settable once from the app, then frozen")
end = text.index("def main():")
fixed = text[:start] + BLOCK + text[end:]

try:
    ast.parse(fixed)
except SyntaxError as exc:
    sys.exit(f"the rules test still does not parse: {exc}")
open(TESTS, "w", encoding="utf-8").write(fixed)
print("rules test parses")

for path in (EXTRAS, ADMIN):
    src = open(path, encoding="utf-8").read()
    if src.count("{") != src.count("}") or src.count("(") != src.count(")"):
        sys.exit(f"unbalanced brackets in {path}")
print("kotlin files balanced")
