"""Raise the admin PIN minimum from 4 to 6 digits (a 4-digit PIN with a
readable hash is only 10k guesses)."""
import sys

EXTRAS = "app/src/main/java/com/camiguinmarket/app/Extras.kt"
TOOL = "scripts/admin-pin-hash.py"

e = open(EXTRAS, encoding="utf-8").read()

pairs = [
    ('                } else if (typed.length < 4) {\n                    error = "Use at least 4 digits."',
     '                } else if (typed.length < 6) {\n                    error = "Use at least 6 digits."'),
    ('label = { Text(if (configured) "PIN" else "New PIN") }',
     'label = { Text(if (configured) "PIN" else "New PIN (6+ digits)") }'),
]
for old, new in pairs:
    if e.count(old) != 1:
        sys.exit(f"anchor not unique: {old[:60]}")
    e = e.replace(old, new)
open(EXTRAS, "w", encoding="utf-8").write(e)

h = open(TOOL, encoding="utf-8").read()
h = h.replace('if len(pin) < 4 or not pin.isdigit():\n    sys.exit("Use at least 4 digits.")',
              'if len(pin) < 6 or not pin.isdigit():\n    sys.exit("Use at least 6 digits.")')
if '"Use at least 6 digits."' not in h:
    sys.exit("hash tool anchor missing")
open(TOOL, "w", encoding="utf-8").write(h)

src = open(EXTRAS, encoding="utf-8").read()
if src.count("{") != src.count("}") or src.count("(") != src.count(")"):
    sys.exit("unbalanced brackets in Extras.kt")
print("6-digit minimum applied")
