# SPDX-License-Identifier: GPL-3.0-only
# Syna license audit tool, Copyright (C) 2026 Verlintas

#!/usr/bin/env python3
"""License audit for Syna: scan the Gradle dependency cache, extract each
artifact's declared license from its POM, and classify compatibility with
GPL-3.0.

Usage:
    python3 tools/license-audit/audit.py [--gradle-cache ~/.gradle/caches/modules-2/files-2.1]
                                         [--only-runtime composeApp/build/deps-runtime.txt]

The --only-runtime flag restricts the report to artifacts actually on the
runtime classpath (paste the output of
`./gradlew :composeApp:dependencies --configuration desktopRuntimeClasspath`
into a file first).
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

APACHE = re.compile(r"apache", re.I)
MIT = re.compile(r"\bmit\b", re.I)
BSD = re.compile(r"\bbsd\b", re.I)
GPL = re.compile(r"gpl", re.I)
LGPL = re.compile(r"lgpl", re.I)
AGPL = re.compile(r"agpl", re.I)
MPL = re.compile(r"mpl", re.I)
EPL = re.compile(r"epl", re.I)
UNKNOWN = "UNKNOWN"

def classify(name):
    if not name:
        return UNKNOWN
    if GPL.search(name) and not LGPL.search(name) and not AGPL.search(name):
        return "GPL" if not AGPL.search(name) else "AGPL"
    if AGPL.search(name):
        return "AGPL"
    if LGPL.search(name):
        return "LGPL"
    if APACHE.search(name):
        return "Apache-2.0"
    if MIT.search(name) and not re.search(r"mit license", name, re.I) or name.strip().lower() == "mit":
        return "MIT"
    if BSD.search(name):
        return "BSD"
    if MPL.search(name):
        return "MPL"
    if EPL.search(name):
        return "EPL"
    return UNKNOWN

def extract_pom_licenses(pom_path):
    try:
        tree = ET.parse(pom_path)
    except Exception:
        return []
    root = tree.getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    names = []
    for lic in root.findall(".//m:licenses/m:license", ns):
        name = lic.findtext("m:name", default="", namespaces=ns) or lic.findtext("m:url", default="", namespaces=ns)
        if name:
            names.append(name.strip())
    return names

def scan_cache(cache_dir):
    result = {}
    if not os.path.isdir(cache_dir):
        print(f"[!] cache dir not found: {cache_dir}", file=sys.stderr)
        return result
    for group in sorted(os.listdir(cache_dir)):
        gdir = os.path.join(cache_dir, group)
        if not os.path.isdir(gdir):
            continue
        for artifact in sorted(os.listdir(gdir)):
            adir = os.path.join(gdir, artifact)
            if not os.path.isdir(adir):
                continue
            for version in sorted(os.listdir(adir)):
                vdir = os.path.join(adir, version)
                if not os.path.isdir(vdir):
                    continue
                for root, _, files in os.walk(vdir):
                    for f in files:
                        if f.endswith(".pom"):
                            key = f"{group}:{artifact}:{version}"
                            result.setdefault(key, extract_pom_licenses(os.path.join(root, f)))
    return result

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gradle-cache", default=os.path.expanduser("~/.gradle/caches/modules-2/files-2.1"))
    ap.add_argument("--only-runtime", default=None, help="file with `dependencies --configuration` output to filter")
    args = ap.parse_args()

    licenses = scan_cache(args.gradle_cache)

    runtime_filter = None
    if args.only_runtime:
        runtime_filter = set()
        with open(args.only_runtime, encoding="utf-8", errors="ignore") as f:
            for line in f:
                m = re.search(r"(\S+:\S+:\S+)", line)
                if m:
                    runtime_filter.add(m.group(1))

    from collections import Counter
    summary = Counter()
    rows = []
    for key in sorted(licenses):
        if runtime_filter and key not in runtime_filter and not any(
            key.split(":")[0:2] == k.split(":")[0:2] for k in runtime_filter
        ):
            continue
        declared = licenses[key] or ["(no license declared)"]
        classified = [classify(n) for n in declared]
        summary.update(classified)
        rows.append((key, declared, classified))

    print("=" * 100)
    print("SYNA DEPENDENCY LICENSE AUDIT (GPL-3.0 compatibility)")
    print("=" * 100)
    for key, declared, classified in rows:
        print(f"{key}")
        for d, c in zip(declared, classified):
            flag = "OK " if c in ("Apache-2.0", "MIT", "BSD", "MPL", "EPL") else ("!!!" if c in ("GPL", "AGPL", "LGPL") else "?? ")
            print(f"    [{flag}] {c:10s} {d}")
    print("-" * 100)
    print("SUMMARY:")
    for c, n in summary.most_common():
        print(f"    {c:10s} {n}")
    print("=" * 100)
    risky = [k for k, _, c in rows for x in c if x in ("GPL", "AGPL", "LGPL", UNKNOWN)]
    if risky:
        print("[!] REVIEW NEEDED for:", risky)
        sys.exit(1)
    print("[OK] No GPL/AGPL/LGPL or unknown-license runtime dependencies found.")
    sys.exit(0)

if __name__ == "__main__":
    main()
