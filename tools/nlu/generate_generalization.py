#!/usr/bin/env python3
"""Build generalization eval set — structurally diverse, never used for template design.

Usage:
  python tools/nlu/generate_generalization.py
  python tools/nlu/generate_generalization.py --write
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from difflib import SequenceMatcher
from pathlib import Path

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from data_generation import (  # noqa: E402
    COMPOSITIONAL,
    CONFUSABLE,
    generate_none_compositional,
    _fill_pattern,
)

HOLDOUT_PATH = ROOT / "holdout" / "holdout.json"
OUT_PATH = ROOT / "holdout" / "generalization.json"
SEED = 2026


def _load_holdout_texts() -> set[str]:
    if not HOLDOUT_PATH.exists():
        return set()
    data = json.loads(HOLDOUT_PATH.read_text(encoding="utf-8"))
    return {s["text"].strip() for s in data.get("samples", [])}


def _similar(a: str, b: str) -> float:
    return SequenceMatcher(None, a, b).ratio()


def _too_close(text: str, blocked: set[str], threshold: float = 0.82) -> bool:
    if text in blocked:
        return True
    for other in blocked:
        if _similar(text, other) >= threshold:
            return True
    return False


def generate_samples(rng: random.Random, holdout_texts: set[str]) -> list[dict]:
    blocked = set(holdout_texts)
    samples: list[dict] = []
    idx = 0

    def add(intent: str, text: str, notes: str = "") -> None:
        nonlocal idx
        t = text.strip()
        if not t or _too_close(t, blocked):
            return
        idx += 1
        row = {"id": f"g-{intent[:6]}-{idx:03d}", "text": t, "intent": intent, "source": "synthetic"}
        if notes:
            row["notes"] = notes
        samples.append(row)
        blocked.add(t)

    intents = [k for k in COMPOSITIONAL if k != "none"]
    for intent in intents:
        patterns = COMPOSITIONAL[intent]
        seen = 0
        attempts = 0
        while seen < 6 and attempts < 60:
            attempts += 1
            text = _fill_pattern(rng.choice(patterns), intent, rng)
            if _too_close(text, blocked):
                continue
            add(intent, text, "compositional")
            seen += 1

    for text, intent in CONFUSABLE:
        add(intent, text, "confusable boundary")

    # Extra none: patterns not in holdout negatives
    rng_none = random.Random(SEED + 1)
    for text, _ in generate_none_compositional(rng_none, count=30):
        add("none", text, "none compositional")

    return samples


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="Write holdout/generalization.json")
    args = parser.parse_args()

    holdout = _load_holdout_texts()
    rng = random.Random(SEED)
    samples = generate_samples(rng, holdout)

    by_intent: dict[str, int] = {}
    for s in samples:
        by_intent[s["intent"]] = by_intent.get(s["intent"], 0) + 1

    payload = {
        "version": 1,
        "description": "Generalization eval — never fed into TEMPLATES; compositional + confusable only.",
        "stats": {"total": len(samples), "by_intent": by_intent},
        "samples": samples,
    }

    print(f"Generated {len(samples)} generalization samples (blocked against {len(holdout)} hold-out texts)")
    print(f"  by_intent: {by_intent}")

    if args.write:
        OUT_PATH.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Wrote {OUT_PATH}")
    else:
        print("Dry run. Pass --write to save.")


if __name__ == "__main__":
    main()
