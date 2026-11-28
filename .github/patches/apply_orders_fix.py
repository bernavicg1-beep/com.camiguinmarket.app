"""Repair the corrupted Orders.kt pushed earlier.

Three defects came in with the last push:
  1. a duplicated `val file` / writeText pair (conflicting declarations)
  2. a stray trailing line that is not Kotlin at all
  3. the seller's chat button labelled "Chat with seller" for buyers too
"""
import sys

PATH = "app/src/main/java/com/camiguinmarket/app/Orders.kt"

src = open(PATH, encoding="utf-8").read()
lines = src.split("\n")

# 1. drop the stray trailing line
lines = [line for line in lines if not line.startswith("[read_files:")]

# 2. drop the duplicated pair (the second `val file` for sales.csv)
dup = '    val file = File(context.cacheDir, "sales.csv")'
hits = [i for i, line in enumerate(lines) if line == dup]
if len(hits) != 2:
    sys.exit("expected two sales.csv lines, found %d" % len(hits))
if not lines[hits[1] + 1].startswith("    file.writeText(lines.joinToString("):
    sys.exit("unexpected line after the duplicated declaration: %r" % lines[hits[1] + 1])
del lines[hits[1]:hits[1] + 2]

# 3. the buyer sees the seller, the seller sees the buyer
want = '            Text(if (isSeller) "Chat with seller" else "Chat with seller")'
fixed = '            Text(if (isSeller) "Chat with buyer" else "Chat with seller")'
if lines.count(want) != 1:
    sys.exit("chat label line not found exactly once")
lines[lines.index(want)] = fixed

open(PATH, "w", encoding="utf-8").write("\n".join(lines))
print("Orders.kt repaired: %d lines" % len(lines))
