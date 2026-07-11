#!/usr/bin/env python3
"""Merge expand_samples.json into holdout.json with dedup + train leak check."""
from __future__ import annotations

import importlib.util
import json
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent
HOLDOUT = ROOT / "holdout.json"
EXPAND = ROOT / "expand_samples.json"
TRAIN = ROOT.parent / "train_and_export.py"


def load_train_texts() -> set[str]:
    from sklearn.model_selection import train_test_split

    spec = importlib.util.spec_from_file_location("train_and_export", TRAIN)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    texts, labels = mod.augment_dataset()
    train_texts, _, _, _ = train_test_split(
        texts, labels, test_size=0.12, random_state=mod.SEED, stratify=labels,
    )
    return set(train_texts), mod.INTENTS


def main() -> None:
    base = json.loads(HOLDOUT.read_text(encoding="utf-8"))
    extra = json.loads(EXPAND.read_text(encoding="utf-8"))
    train_texts, intents = load_train_texts()

    by_id: dict[str, dict] = {}
    by_text: dict[str, dict] = {}
    for row in base["samples"] + extra:
        text = row["text"].strip()
        if not text:
            continue
        if text in by_text:
            print(f"skip duplicate text: {text!r} ({row['id']})")
            continue
        if row["id"] in by_id:
            print(f"skip duplicate id: {row['id']}")
            continue
        by_id[row["id"]] = row
        by_text[text] = row

    samples = list(by_id.values())
    leaks = [s for s in samples if s["text"].strip() in train_texts]
    if leaks:
        print("LEAK into train split:")
        for s in leaks:
            print(f"  {s['id']}: {s['text']!r}")
        sys.exit(1)

    unknown = [s for s in samples if s["intent"] not in intents]
    if unknown:
        print("Unknown intents:", {s["intent"] for s in unknown})
        sys.exit(1)

    counts = Counter(s["intent"] for s in samples)
    sources = Counter(s.get("source", "?") for s in samples)

    out = {
        **base,
        "version": 2,
        "updated": "2026-07-11",
        "description": "人工撰写 + 真机 ASR 转写 hold-out；禁止加入 train_and_export.py 训练管线",
        "stats": {
            "total": len(samples),
            "by_source": dict(sources),
            "by_intent": dict(sorted(counts.items())),
        },
        "samples": sorted(samples, key=lambda s: (s["intent"], s["id"])),
    }
    HOLDOUT.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(samples)} samples to {HOLDOUT}")
    print(f"Sources: {dict(sources)}")
    print("Per intent:")
    for intent in intents:
        print(f"  {intent:28s} {counts.get(intent, 0):3d}")


if __name__ == "__main__":
    main()
