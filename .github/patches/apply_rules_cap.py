"""Carry over the last local change and drop the one-shot push scaffolding."""

PATH = "firestore.rules"
src = open(PATH, encoding="utf-8").read()

anchor = "        && request.resource.data.reason.size() > 0\n"
with_cap = anchor + "        && request.resource.data.reason.size() <= 80\n"

if with_cap in src:
    print("reason cap already present")
elif src.count(anchor) == 1:
    open(PATH, "w", encoding="utf-8").write(src.replace(anchor, with_cap))
    print("reason cap added")
else:
    raise SystemExit("anchor found %d times" % src.count(anchor))
