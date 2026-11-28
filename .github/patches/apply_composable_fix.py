"""Drop the duplicated @Composable that landed above GcashSection."""
import sys

PATH = "app/src/main/java/com/camiguinmarket/app/Extras.kt"
src = open(PATH, encoding="utf-8").read()

dup = "@Composable\n@Composable\nfun GcashSection(uid: String) {"
single = "@Composable\nfun GcashSection(uid: String) {"

if src.count(single) == 1 and src.count(dup) == 0:
    print("already correct")
elif src.count(dup) == 1:
    open(PATH, "w", encoding="utf-8").write(src.replace(dup, single))
    print("duplicate removed")
else:
    sys.exit("unexpected annotation count: dup=%d single=%d" % (src.count(dup), src.count(single)))
