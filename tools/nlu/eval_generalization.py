#!/usr/bin/env python3
"""Evaluate on generalization set (orthogonal to hold-out paraphrase tuning).

Usage:
  python tools/nlu/eval_generalization.py
  python tools/nlu/eval_generalization.py --model-dir tools/nlu/.work/finetuned
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer

ROOT = Path(__file__).resolve().parent
GEN_PATH = ROOT / "holdout" / "generalization.json"
TRAIN_SCRIPT = ROOT / "train_and_export.py"
DEFAULT_MODEL_DIR = ROOT / ".work" / "finetuned"
MAX_LENGTH = 64


def load_train_module():
    spec = importlib.util.spec_from_file_location("train_and_export", TRAIN_SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def predict_with_probs(model, tokenizer, texts: list[str], intents: list[str]):
    model.eval()
    preds: list[str] = []
    confs: list[float] = []
    margins: list[float] = []
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
            probs = np.exp(logits - logits.max())
            probs = probs / probs.sum()
            ranked_idx = np.argsort(probs)[::-1]
            top, second = int(ranked_idx[0]), int(ranked_idx[1])
            preds.append(intents[top])
            confs.append(float(probs[top]))
            margins.append(float(probs[top] - probs[second]))
    return preds, confs, margins


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, default=DEFAULT_MODEL_DIR)
    args = parser.parse_args()

    if not GEN_PATH.exists():
        sys.exit(f"Missing {GEN_PATH}. Run: python tools/nlu/generate_generalization.py --write")

    data = json.loads(GEN_PATH.read_text(encoding="utf-8"))
    samples = data["samples"]
    train_mod = load_train_module()
    intents = train_mod.INTENTS

    if not args.model_dir.exists():
        sys.exit(f"Model not found: {args.model_dir}")

    tokenizer = AutoTokenizer.from_pretrained(args.model_dir)
    model = AutoModelForSequenceClassification.from_pretrained(args.model_dir)

    texts = [s["text"] for s in samples]
    labels = [s["intent"] for s in samples]
    preds, confs, margins = predict_with_probs(model, tokenizer, texts, intents)

    correct = sum(p == g for p, g in zip(preds, labels))
    total = len(samples)
    by_intent = defaultdict(lambda: [0, 0])
    errors = []

    for row, pred, conf, margin in zip(samples, preds, confs, margins):
        gold = row["intent"]
        by_intent[gold][1] += 1
        if pred == gold:
            by_intent[gold][0] += 1
        else:
            errors.append((row["id"], row["text"], gold, pred, conf, margin))

    print(f"\n=== Generalization ({total} samples) ===")
    print(f"Accuracy: {correct}/{total} = {100 * correct / total:.1f}%")
    print(f"Mean confidence: {np.mean(confs):.3f}  |  Mean top1-top2 margin: {np.mean(margins):.3f}")

    low_margin = sum(1 for m in margins if m < 0.15)
    print(f"Low-margin (<0.15) predictions: {low_margin}/{total} ({100 * low_margin / total:.1f}%)")

    print("\n--- Per intent (recall) ---")
    for intent in intents:
        hit, n = by_intent.get(intent, [0, 0])
        if n == 0:
            continue
        print(f"  {intent:28s} {hit:2d}/{n:2d}  ({100 * hit / n:.1f}%)")

    if errors:
        print(f"\n--- Errors ({len(errors)}) ---")
        for sid, text, gold, pred, conf, margin in errors[:25]:
            print(f"  [{sid}] gold={gold} pred={pred} conf={conf:.2f} margin={margin:.2f}  {text}")
        if len(errors) > 25:
            print(f"  ... and {len(errors) - 25} more")


if __name__ == "__main__":
    main()
