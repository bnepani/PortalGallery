#!/usr/bin/env python3
"""
Scrubs a Google Photos shared-album page into a committable test fixture.

The raw page contains: the live share link, share ?key= access tokens, contributor
real names, 21-digit Google account (Gaia) IDs, avatar URLs, and live photo URLs.
Anyone who can read the repo could otherwise open the album.

Structure, entry count, ordering and timestamps are preserved so the golden test
remains meaningful. Only identifying values are rewritten, each to a deterministic
same-shape replacement so JSON stays valid and distinct ids stay distinct.

Usage:
    python3 tools/scrub_fixture.py album-fixture.html \
        app/src/test/resources/shared_album_fixture.html
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
    html = open(src, encoding="utf-8", errors="replace").read()
    original_len = len(html)
    report = []

    # 1. Photo URL slugs (keep AP1Gcz prefix and length so parsers behave identically)
    slugs = sorted(set(re.findall(r"lh3\.googleusercontent\.com/pw/([A-Za-z0-9_-]+)", html)))
    for s in slugs:
        html = html.replace(s, fake(s, len(s), "AP1Gcz"))
    report.append(f"photo slugs      : {len(slugs)}")

    # 2. Avatar / profile image paths
    avatars = sorted(set(re.findall(r"lh3\.googleusercontent\.com/a/([A-Za-z0-9_-]+)", html)))
    for a in avatars:
        html = html.replace(a, fake(a, len(a)))
    report.append(f"avatar paths     : {len(avatars)}")

    # 3. Share access tokens
    keys = sorted(set(re.findall(r"[?&]key=([A-Za-z0-9_-]+)", html)))
    for k in keys:
        html = html.replace(k, fake(k, len(k)))
    report.append(f"share keys       : {len(keys)}")

    # 4. Album ids (AF1Qip…) — the share identifier itself
    album_ids = sorted(set(re.findall(r"\bAF1Qip[A-Za-z0-9_-]{20,}", html)))
    for a in album_ids:
        html = html.replace(a, fake(a, len(a), "AF1Qip"))
    report.append(f"album/media ids  : {len(album_ids)}")

    # 5. Gaia account ids
    gaia = sorted(set(re.findall(r"\b1[0-9]{20}\b", html)))
    for g in gaia:
        html = html.replace(g, "1" + fake(g, 20).translate(
            str.maketrans(ALPHABET, "0123456789" * 6 + "0123")))
    report.append(f"gaia ids         : {len(gaia)}")

    # 6. Contributor names
    names = sorted(set(re.findall(r'\["([A-Z][a-z]+ [A-Z][a-z]+)",1,null', html)))
    for i, n in enumerate(names):
        first = n.split()[0]
        html = html.replace(f'"{n}"', f'"Test User{i + 1}"')
        html = re.sub(rf'"{re.escape(first)}"', f'"Test{i + 1}"', html)
    report.append(f"contributor names: {len(names)} -> {names if names else '(none)'}")

    # 7. Short share link
    html = re.sub(r"photos\.app\.goo\.gl/[A-Za-z0-9]+", "photos.app.goo.gl/TESTFIXTURE01", html)

    # 8. Album title
    html = re.sub(r'(<meta property="og:title" content=")[^"]*(")',
                  r"\1Test Album\2", html)

    open(dst, "w", encoding="utf-8").write(html)

    print("\n".join(report))
    print(f"\nwrote {dst}  ({original_len:,} -> {len(html):,} bytes)")

    # Verify nothing identifying survived. Check for the ORIGINAL values, not for the
    # patterns — the replacements are deliberately the same shape as what they replace.
    leaks = []
    if re.search(r"photos\.app\.goo\.gl/(?!TESTFIXTURE01)", html):
        leaks.append("share link")
    for original, label in (
        [(g, "gaia id") for g in gaia]
        + [(k, "share key") for k in keys]
        + [(n, "name") for n in names]
        + [(s, "photo slug") for s in slugs]
        + [(a, "album/media id") for a in album_ids]
    ):
        if original in html:
            leaks.append(f"{label} {original[:16]!r}")

    if leaks:
        sys.exit(f"\n!! SCRUB INCOMPLETE — still present: {', '.join(leaks)}")
    print("scrub verified: no gaia ids, share links, or contributor names remain")


if __name__ == "__main__":
    main()
