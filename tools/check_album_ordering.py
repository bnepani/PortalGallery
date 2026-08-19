#!/usr/bin/env python3
"""
Settles the C-1 question: does a Google Photos shared-album page return the
OLDEST photos (a fixed prefix) or the NEWEST (a rolling window)?

This is the blocker for PortalGallery's auto-refresh requirement. If the page
returns the oldest N, photos added today never reach the frame.

Usage:
    python3 tools/check_album_ordering.py "<share link>"
    python3 tools/check_album_ordering.py --file album-fixture.html

Run it against an album that is ACTIVELY RECEIVING photos. A closed trip album
cannot answer the question.
"""

import json
import re
import sys
import subprocess
from datetime import datetime, timezone

UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36"


def fetch(url):
    out = subprocess.run(
        ["curl", "-sL", "--max-time", "60", "-A", UA, url],
        capture_output=True, timeout=90,
    )
    if out.returncode != 0:
        sys.exit(f"curl failed (exit {out.returncode})")
    return out.stdout.decode("utf-8", "replace")


def extract_ds(html, key):
    """Pull the `data:` array out of an AF_initDataCallback block by ds key."""
    anchor = html.find(f"key: '{key}'")
    if anchor == -1:
        return None
    start = html.find("data:", anchor)
    if start == -1:
        return None
    start = html.find("[", start)
    depth, in_str, esc = 0, False, False
    for i in range(start, len(html)):
        c = html[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
        elif c == "[":
            depth += 1
        elif c == "]":
            depth -= 1
            if depth == 0:
                try:
                    return json.loads(html[start:i + 1])
                except json.JSONDecodeError as e:
                    sys.exit(f"ds:{key} did not parse as JSON: {e}")
    return None


def ts(ms):
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)

    if sys.argv[1] == "--file":
        html = open(sys.argv[2], encoding="utf-8", errors="replace").read()
        src = sys.argv[2]
    else:
        html = fetch(sys.argv[1])
        src = sys.argv[1]

    title = re.search(r'<meta property="og:title" content="([^"]*)"', html)
    print(f"source : {src}")
    print(f"album  : {title.group(1) if title else '(no og:title)'}")
    print(f"bytes  : {len(html):,}")

    ds1 = extract_ds(html, "ds:1")
    if not ds1 or len(ds1) < 2 or not isinstance(ds1[1], list):
        sys.exit("could not locate the ds:1 photo array — page structure may have changed")

    entries = [e for e in ds1[1] if isinstance(e, list) and len(e) > 5]
    print(f"photos : {len(entries)}")

    token = ds1[2] if len(ds1) > 2 and isinstance(ds1[2], str) else None
    print(f"more   : {'yes — continuation token present' if token else 'no — this is the whole album'}")

    capture = [e[2] for e in entries if isinstance(e[2], int) and e[2] > 1_000_000_000_000]
    added = [e[5] for e in entries if isinstance(e[5], int) and e[5] > 1_000_000_000_000]
    if not capture:
        sys.exit("no capture timestamps parsed — structure changed")

    inversions = sum(1 for a, b in zip(capture, capture[1:]) if a > b)
    ascending = inversions < len(capture) * 0.1

    print()
    print(f"capture: {ts(min(capture)):%Y-%m-%d} .. {ts(max(capture)):%Y-%m-%d}")
    if added:
        print(f"added  : {ts(min(added)):%Y-%m-%d} .. {ts(max(added)):%Y-%m-%d}")
    print(f"order  : {'ASCENDING (oldest first)' if ascending else 'DESCENDING (newest first)'}"
          f"  [{inversions}/{len(capture) - 1} inversions]")

    newest = ts(max(added or capture))
    age_days = (datetime.now(timezone.utc) - newest).days
    print(f"newest : {newest:%Y-%m-%d} ({age_days} days old)")

    print()
    print("=" * 62)
    if not token:
        print("VERDICT: the page returns the ENTIRE album.")
        print("  Ordering is irrelevant. Auto-refresh works. Keep the album")
        print("  under ~300 photos and nothing else is needed.")
    elif ascending:
        print("VERDICT: OLDEST-FIRST PREFIX. Auto-refresh is BROKEN.")
        print("  New photos append to the tail; the page serves the head.")
        print("  Photos added today will never reach the frame.")
        print("  -> Choose: cap the album at ~300, rotate multiple albums,")
        print("     or paginate via the batchexecute RPC.")
    elif age_days <= 7:
        print("VERDICT: NEWEST-FIRST WINDOW. Auto-refresh WORKS.")
        print("  The design holds as originally written.")
    else:
        print("VERDICT: descending order, but the newest photo is")
        print(f"  {age_days} days old. If this album received a photo more")
        print("  recently than that, the page is NOT serving the true newest.")
        print("  Re-run against an album with a photo added in the last day.")
    print("=" * 62)


if __name__ == "__main__":
    main()
