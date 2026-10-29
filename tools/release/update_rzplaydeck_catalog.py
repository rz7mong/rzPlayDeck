#!/usr/bin/env python3
"""Regenerate catalog.json for rzPlayDeck from its own GitHub releases.

Reads the latest published, non-draft release of rz7mong/rzPlayDeck that
carries a signed rzPlayDeck APK asset, then writes catalog.json in the
format AppUpdates.readPublishedCatalog expects: schema 1, sourceRepo and
ciRepo both rz7mong/rzPlayDeck, and a download URL under that repo.
Only stdlib is used. Exits 0 with no change when no suitable release
exists, so a Debian-only repo never produces a broken catalog.
"""
import hashlib
import json
import os
import re
import sys
import time
import urllib.request

REPO = "rz7mong/rzPlayDeck"
API = f"https://api.github.com/repos/{REPO}"
PACKAGE = "id.rzmong.rzplaydeck"


def api(path):
    req = urllib.request.Request(API + path, headers={
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
        "User-Agent": "rzPlayDeck-catalog",
    })
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def download_sha256(url, token):
    req = urllib.request.Request(url, headers={
        "Accept": "application/octet-stream",
        "Authorization": f"Bearer {token}",
        "User-Agent": "rzPlayDeck-catalog",
    })
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(req, timeout=600) as resp:
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def main():
    token = os.environ["GITHUB_TOKEN"]
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    signer = open(os.path.join(root, "keystore/release-signer.sha256")).read().strip()
    gradle = open(os.path.join(root, "app/build.gradle")).read()
    version_code = int(re.search(r"versionCode\s+(\d+)", gradle).group(1))
    version = re.search(r'versionName\s+"([^"]+)"', gradle).group(1)

    releases = api("/releases?per_page=30")
    chosen = None
    chosen_apk = None
    for rel in releases:
        if rel.get("draft") or rel.get("prerelease"):
            continue
        for asset in rel.get("assets", []):
            name = asset.get("name", "")
            if name.endswith(".apk") and name.startswith("rzPlayDeck-"):
                chosen, chosen_apk = rel, asset
                break
        if chosen:
            break
    if not chosen:
        print("No rzPlayDeck APK release found; catalog left unchanged.")
        return 0

    target = chosen.get("target_commitish") or "main"
    commit = target if re.fullmatch(r"[0-9a-f]{40}", target) else api(f"/commits/{target}")["sha"]
    published = chosen["published_at"]
    published_ms = int(time.mktime(time.strptime(published, "%Y-%m-%dT%H:%M:%SZ")) * 1000)
    sha256, size = download_sha256(chosen_apk["browser_download_url"], token)
    summary = (chosen.get("body") or "").strip().split("\n")[0][:500]
    catalog = {
        "schema": 1,
        "sourceRepo": REPO,
        "ciRepo": REPO,
        "stable": {
            "tag": chosen["tag_name"],
            "title": chosen.get("name") or chosen["tag_name"],
            "summary": summary,
            "commit": commit,
            "pr": 0,
            "version": version,
            "versionCode": version_code,
            "publishedAt": published_ms,
            "url": chosen["html_url"],
            "apks": {PACKAGE: {
                "name": chosen_apk["name"],
                "url": chosen_apk["browser_download_url"],
                "size": size,
                "sha256": sha256,
                "packageName": PACKAGE,
                "versionCode": version_code,
                "signerSha256": signer,
            }},
        },
        "preview": None,
        "tests": [],
        "recentPreviews": [],
    }
    with open(os.path.join(root, "catalog.json"), "w") as f:
        json.dump(catalog, f, indent=2)
        f.write("\n")
    print(f"Wrote catalog.json from release {chosen['tag_name']} ({commit[:7]}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
