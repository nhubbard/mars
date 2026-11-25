#!/usr/bin/env python3
"""Repeatable textual inventory of tracked Kotlin sources (not generated build output)."""
import argparse
import json
import re
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument(
    "--non-null-inventory", action="store_true",
    help="list production assertions by file and line instead of aggregate metrics",
)
options = parser.parse_args()

ROOT = Path(__file__).resolve().parents[1]
paths = sorted(
    set(
        subprocess.check_output(
            [
                "git", "ls-files", "--cached", "--others", "--exclude-standard",
                "*.kt", "*.kts",
            ],
            cwd=ROOT,
            text=True,
        ).splitlines()
    )
)
metrics = dict(
    kotlin_files=len(paths),
    test_class_tokens=0,
    other_class_tokens=0,
    test_anonymous_objects=0,
    other_anonymous_objects=0,
    dagger_builders=0,
    raw_dynamic_bundles=0,
    production_non_null_assertions=0,
)
non_null_inventory = []
for name in paths:
    source = (ROOT / name).read_text()
    group = "test" if "/src/test/" in name else "other"
    metrics[group + "_class_tokens"] += len(
        re.findall(r"::class\.java(?:ObjectType|PrimitiveType)?\b", source)
    )
    metrics[group + "_anonymous_objects"] += len(re.findall(r"\bobject\s*:", source))
    metrics["dagger_builders"] += len(
        re.findall(r"DaggerMarsComponent\s*\.builder\s*\(", source)
    )
    metrics["raw_dynamic_bundles"] += len(re.findall(r"\bDynamicBundle\s*\(", source))
    if "/src/main/" in name:
        count = source.count("!!")
        metrics["production_non_null_assertions"] += count
        if count:
            non_null_inventory.append({
                "file": name,
                "count": count,
                "lines": [i for i, line in enumerate(source.splitlines(), 1) if "!!" in line],
            })
print(json.dumps(non_null_inventory if options.non_null_inventory else metrics, indent=2))
