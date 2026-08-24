#!/usr/bin/env python3
"""Découpe déterministe des flows Maestro pour N runners à 1 device."""
from __future__ import annotations

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]  # Maestro/
TESTS = ROOT / "tests"
WIP = re.compile(r"^\s*-\s*wip\s*$", re.MULTILINE)


def is_wip(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    head = text.split("\n---\n", 1)[0]
    return WIP.search(head) is not None


def list_flows() -> list[Path]:
    return [p for p in sorted(TESTS.rglob("*.yaml")) if not is_wip(p)]


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--index", type=int, required=True)
    p.add_argument("--count", type=int, default=2)
    p.add_argument("--write-config", type=Path)
    args = p.parse_args()
    if not 0 <= args.index < args.count:
        raise SystemExit(f"index must be in 0..{args.count - 1}")

    shard = [f for i, f in enumerate(list_flows()) if i % args.count == args.index]
    rel = [f.relative_to(ROOT).as_posix() for f in shard]
    if not rel:
        raise SystemExit(f"shard {args.index} is empty")

    print(f"shard {args.index}/{args.count}: {len(rel)} flows")
    for r in rel:
        print(f"  - {r}")

    if args.write_config:
        args.write_config.write_text(
            "# generated — do not commit\n"
            + "flows:\n"
            + "".join(f"  - {r}\n" for r in rel)
            + "excludeTags:\n  - wip\n"
            + "testOutputDir: build/maestro-results\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    main()
