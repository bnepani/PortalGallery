#!/usr/bin/env python3
"""
Scrubs a `snAcKc` batchexecute response into a committable test fixture.

The sibling of scrub_fixture.py, for the pagination RPC rather than the share page.
A raw response carries the same capability material — media ids, live photo URLs,
share access tokens — plus the continuation token, which is the thing that lets a
holder walk the rest of the album.

Two rules that differ from the HTML scrubber:

  1. **Length is preserved exactly.** Every replacement is the same length as what it
     replaces, and nothing is added or removed. The response is framed by byte counts,
     and although AlbumPager ignores them (they are off by one against the JSON they
     introduce — see design-v2 §11c), a fixture whose counts no longer match its
     payload would be a poor test of a parser whose whole job is that framing.

  2. **The continuation token is scrubbed.** It is not merely identifying, it is
     functional: anyone holding one plus the album id can fetch the next 300 items.

Structure, entry count, ordering, dimensions and timestamps are untouched, so a golden
test over the fixture still means something.

Usage:
    python3 tools/scrub_rpc_fixture.py /tmp/rpc-page2.raw \
        app/src/test/resources/rpc_page2_fixture.txt
"""

import hashlib
import re
import sys

ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"


def fake(seed: str, length: int, prefix: str = "") -> str:
    """Deterministic same-length replacement, so distinct inputs stay distinct."""
    out = []
    h = hashlib.sha256(seed.encode()).digest()
    i = 0
    while len(out) < length - len(prefix):
        if i >= len(h):
            h = hashlib.sha256(h).digest()
            i = 0
        out.append(ALPHABET[h[i] % len(ALPHABET)])
        i += 1
    return prefix + "".join(out)


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)

    src, dst = sys.argv[1], sys.argv[2]
    body = open(src, encoding="utf-8", errors="replace").read()
    original_len = len(body)
    report = []

    # Order matters: media ids share the AF1Qip prefix with the album id, so the broad
    # id sweep has to run before anything that could partially rewrite one.

    # 1. Media and album ids.
    ids = sorted(set(re.findall(r"\bAF1Qip[A-Za-z0-9_-]{20,}", body)))
    for a in ids:
        body = body.replace(a, fake(a, len(a), "AF1Qip"))
    report.append(f"media/album ids   : {len(ids)}")

    # 2. Photo URL slugs.
    slugs = sorted(set(re.findall(r"lh3\.googleusercontent\.com/pw/([A-Za-z0-9_-]+)", body)))
    for s in slugs:
        body = body.replace(s, fake(s, len(s), "AP1Gcz"))
    report.append(f"photo slugs       : {len(slugs)}")

    # 3. Continuation tokens — functional, not just identifying.
    tokens = sorted(set(re.findall(r"\bAH_uQ4[A-Za-z0-9_\-]{40,}", body)))
    for t in tokens:
        body = body.replace(t, fake(t, len(t), "AH_uQ4"))
    report.append(f"continuation tokens: {len(tokens)}")

    # 4. Avatar paths.
    avatars = sorted(set(re.findall(r"lh3\.googleusercontent\.com/a/([A-Za-z0-9_-]+)", body)))
    for a in avatars:
        body = body.replace(a, fake(a, len(a)))
    report.append(f"avatar paths      : {len(avatars)}")

    # 5. Share access tokens.
    keys = sorted(set(re.findall(r"[?&]key=([A-Za-z0-9_-]+)", body)))
    for k in keys:
        body = body.replace(k, fake(k, len(k)))
    report.append(f"share keys        : {len(keys)}")

    # 6. Gaia account ids.
    gaia = sorted(set(re.findall(r"\b1[0-9]{20}\b", body)))
    for g in gaia:
        body = body.replace(g, "1" + fake(g, 20).translate(
            str.maketrans(ALPHABET, "0123456789" * 6 + "0123")))
    report.append(f"gaia ids          : {len(gaia)}")

    # 7. Contributor names, as they appear escaped inside the inner JSON string.
    names = sorted(set(re.findall(r'\\"([A-Z][a-z]+ [A-Z][a-z]+)\\"', body)))
    for i, n in enumerate(names):
        body = body.replace(n, f"Test User{i + 1}".ljust(len(n))[:len(n)])
    report.append(f"contributor names : {len(names)}")

    print("\n".join(report))

    if len(body) != original_len:
        sys.exit(f"\n!! LENGTH CHANGED {original_len:,} -> {len(body):,} — framing would break")

    open(dst, "w", encoding="utf-8").write(body)
    print(f"\nwrote {dst}  ({len(body):,} bytes, length preserved)")

    # Verify against the ORIGINAL values, not the patterns — replacements are
    # deliberately the same shape as what they replace, so a pattern check proves nothing.
    leaks = []
    for original, label in (
        [(t, "continuation token") for t in tokens]
        + [(g, "gaia id") for g in gaia]
        + [(k, "share key") for k in keys]
        + [(n, "name") for n in names]
        + [(s, "photo slug") for s in slugs]
        + [(a, "media/album id") for a in ids]
    ):
        if original in body:
            leaks.append(f"{label} {original[:16]!r}")

    if leaks:
        sys.exit(f"\n!! SCRUB INCOMPLETE — still present: {', '.join(sorted(set(leaks))[:8])}")
    print("scrub verified: no ids, tokens, keys or names from the original remain")


if __name__ == "__main__":
    main()
