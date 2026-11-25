#!/usr/bin/env python3
"""Repeatable textual inventory of tracked Kotlin sources (not generated build output)."""
import json
import re
import subprocess
from pathlib import Path

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
        metrics["production_non_null_assertions"] += source.count("!!")
print(json.dumps(metrics, indent=2))
