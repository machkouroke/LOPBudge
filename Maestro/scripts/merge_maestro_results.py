#!/usr/bin/env python3
"""Assemble les arbres maestro-results de tous les shards."""
from __future__ import annotations

import argparse
import shutil
from pathlib import Path

SKIP = {"maestro.log"}  # log de session : on le copie à part, pas comme un flow


def flow_dirs(root: Path) -> list[Path]:
    """Dossiers de flow (enfants des sessions horodatées)."""
    found: list[Path] = []
    if not root.is_dir():
        return found
    for session in sorted(p for p in root.iterdir() if p.is_dir()):
        for child in session.iterdir():
            if child.is_dir():
                found.append(child)
    return found


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("sources", nargs="+", type=Path, help="racines maestro-results (déjà unzip)")
    p.add_argument("-o", "--output", type=Path, required=True)
    args = p.parse_args()

    out = args.output
    if out.exists():
        shutil.rmtree(out)
    merged_session = out / "merged"
    merged_session.mkdir(parents=True)

    seen: set[str] = set()
    n = 0
    for src in args.sources:
        for flow in flow_dirs(src):
            name = flow.name
            if name in seen:
                raise SystemExit(f"collision: flow {name!r} présent dans plusieurs shards")
            seen.add(name)
            shutil.copytree(flow, merged_session / name)
            n += 1

    print(f"merged {n} flow dirs → {merged_session}")


if __name__ == "__main__":
    main()
