#!/usr/bin/env python3
"""Evaluate fine-tuned NLU on hold-out set (human + device ASR transcripts).

Usage:
  python tools/nlu/eval_holdout.py
  python tools/nlu/eval_holdout.py --leak-check-only
  python tools/nlu/eval_holdout.py --model-dir tools/nlu/.work/finetuned
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer

ROOT = Path(__file__).resolve().parent
HOLDOUT_PATH = ROOT / "holdout" / "holdout.json"
TRAIN_SCRIPT = ROOT / "train_and_export.py"
DEFAULT_MODEL_DIR = ROOT / ".work" / "finetuned"
MAX_LENGTH = 64


def load_train_module():
    spec = importlib.util.spec_from_file_location("train_and_export", TRAIN_SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_holdout() -> list[dict]:
    data = json.loads(HOLDOUT_PATH.read_text(encoding="utf-8"))
    return data["samples"]


def build_training_text_set(train_mod) -> set[str]:
    from sklearn.model_selection import train_test_split

    texts, labels = train_mod.augment_dataset()
    train_texts, _, _, _ = train_test_split(
        texts, labels, test_size=0.12, random_state=train_mod.SEED, stratify=labels,
    )
    return set(train_texts)


def leak_check(samples: list[dict], train_texts: set[str]) -> list[str]:
    leaks = []
    for row in samples:
        text = row["text"].strip()
        if text in train_texts:
            leaks.append(f"{row['id']}: exact match in train set -> {text!r}")
    return leaks


def predict(model, tokenizer, texts: list[str], intents: list[str]) -> list[str]:
    model.eval()
    preds: list[str] = []
    with torch.no_grad():
        for text in texts:
            batch = tokenizer(
                text,
                return_tensors="pt",
                truncation=True,
                max_length=MAX_LENGTH,
                padding="max_length",
            )
            logits = model(**batch).logits[0].numpy()
            idx = int(np.argmax(logits))
            preds.append(intents[idx])
    return preds


def print_report(samples: list[dict], preds: list[str], intents: list[str]) -> dict:
    labels = [s["intent"] for s in samples]
    correct = sum(p == g for p, g in zip(preds, labels))
    total = len(samples)

    by_source = defaultdict(lambda: [0, 0])
    by_intent = defaultdict(lambda: [0, 0])
    errors: list[tuple[str, str, str, str]] = []

    for row, pred in zip(samples, preds):
        gold = row["intent"]
        src = row.get("source", "unknown")
        ok = pred == gold
        by_source[src][1] += 1
        by_intent[gold][1] += 1
        if ok:
            by_source[src][0] += 1
            by_intent[gold][0] += 1
        else:
            errors.append((row["id"], row["text"], gold, pred))

    print(f"\n=== Hold-out summary ({total} samples) ===")
    print(f"Accuracy: {correct}/{total} = {100 * correct / total:.1f}%")

    print("\n--- By source ---")
    for src, (hit, n) in sorted(by_source.items()):
        print(f"  {src:12s} {hit:3d}/{n:3d}  ({100 * hit / n:.1f}%)")

    print("\n--- Per intent (recall) ---")
    for intent in intents:
        hit, n = by_intent.get(intent, [0, 0])
        if n == 0:
            continue
        print(f"  {intent:28s} {hit:2d}/{n:2d}  ({100 * hit / n:.1f}%)")

    if errors:
        print(f"\n--- Errors ({len(errors)}) ---")
        for sid, text, gold, pred in errors:
            print(f"  [{sid}] gold={gold} pred={pred}  {text}")

    pending = sum(
        1 for s in samples
        if s.get("source") == "asr_device"
        and (s.get("recording") or {}).get("status") == "pending"
    )
    if pending:
        print(f"\nNote: {pending} asr_device sample(s) still pending real wav.")

    return {
        "accuracy": correct / total,
        "total": total,
        "errors": len(errors),
        "pending_recordings": pending,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, default=DEFAULT_MODEL_DIR)
    parser.add_argument("--leak-check-only", action="store_true")
    args = parser.parse_args()

    if not HOLDOUT_PATH.exists():
        sys.exit(f"Missing {HOLDOUT_PATH}")

    train_mod = load_train_module()
    samples = load_holdout()
    meta = json.loads(HOLDOUT_PATH.read_text(encoding="utf-8"))
    stats = meta.get("stats")
    if stats:
        print(f"Hold-out v{meta.get('version', '?')}: {stats.get('total', len(samples))} samples")
        print(f"  sources: {stats.get('by_source', {})}")
    else:
        print(f"Hold-out samples: {len(samples)}")
    train_texts = build_training_text_set(train_mod)

    leaks = leak_check(samples, train_texts)
    if not stats:
        print(f"Hold-out samples: {len(samples)}")
    print(f"Training unique texts (approx): {len(train_texts)}")
    if leaks:
        print("\n*** LEAK DETECTED (text appears in train split) ***")
        for line in leaks:
            print(f"  {line}")
        if args.leak_check_only:
            sys.exit(1)
    else:
        print("Leak check: OK (no exact text overlap with train split)")

    if args.leak_check_only:
        return

    if not args.model_dir.exists():
        sys.exit(f"Model not found: {args.model_dir}. Run train_and_export.py first.")

    intents = train_mod.INTENTS
    tokenizer = AutoTokenizer.from_pretrained(args.model_dir)
    model = AutoModelForSequenceClassification.from_pretrained(args.model_dir)
    texts = [s["text"] for s in samples]
    preds = predict(model, tokenizer, texts, intents)
    print_report(samples, preds, intents)


if __name__ == "__main__":
    main()
