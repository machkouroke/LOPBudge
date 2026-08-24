#!/usr/bin/env python3
from __future__ import annotations

import argparse
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("inputs", nargs="+", type=Path)
    p.add_argument("-o", "--output", type=Path, required=True)
    args = p.parse_args()

    cases: list[ET.Element] = []
    tests = failures = errors = skipped = 0
    time = 0.0
    seen: set[str] = set()

    for path in args.inputs:
        if not path.is_file():
            continue
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
        for suite in suites:
            tests += int(float(suite.attrib.get("tests", 0)))
            failures += int(float(suite.attrib.get("failures", 0)))
            errors += int(float(suite.attrib.get("errors", 0)))
            skipped += int(float(suite.attrib.get("skipped", 0)))
            time += float(suite.attrib.get("time", 0) or 0)
            for case in suite.findall("testcase"):
                key = f"{case.attrib.get('classname', '')}::{case.attrib.get('name', '')}"
                if key in seen:
                    raise SystemExit(f"duplicate testcase across shards: {key}")
                seen.add(key)
                cases.append(case)

    merged = ET.Element(
        "testsuite",
        {
            "name": "Maestro",
            "tests": str(tests),
            "failures": str(failures),
            "errors": str(errors),
            "skipped": str(skipped),
            "time": f"{time:.3f}",
        },
    )
    merged.extend(cases)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(merged).write(args.output, encoding="utf-8", xml_declaration=True)
    print(f"merged {len(cases)} cases → {args.output}")


if __name__ == "__main__":
    main()
