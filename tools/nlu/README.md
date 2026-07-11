# Offline NLU (Route A — MiniLM-class)

Fine-tune Chinese RoBERTa-Mini (`uer/chinese_roberta_L-4_H-256`, 4L/256d, ~12MB INT8 ONNX) for offline intent routing.

## Train & export

```bash
pip install -r tools/nlu/requirements.txt
python tools/nlu/train_and_export.py
```

Outputs under `app/src/main/assets/nlu/`:

| File | Purpose |
|------|---------|
| `intent_classifier.onnx` | INT8 ONNX (~15–25MB) |
| `vocab.txt` | WordPiece vocabulary |
| `intent_labels.json` | Intent names |
| `encoder_config.json` | Token ids, thresholds |
| `tokenizer_regression.json` | Android tokenizer parity tests |

## Runtime

`OfflineNluRouter` → ONNX transformer intent → rule-based slots → `SystemIntentExecutor`

Falls back to `LocalSystemShortcutResolver` if model assets are missing.

## Hold-out 评测集（人工 / 真机 ASR）

与训练模板隔离，见 `tools/nlu/holdout/`（当前 **183 条**，v2）。

```bash
python tools/nlu/eval_holdout.py              # 泄漏检查 + 准确率
python tools/nlu/eval_holdout.py --leak-check-only
python tools/nlu/holdout/merge_holdout.py     # 合并 expand_samples.json 后重建
```

新增样本：编辑 `expand_samples.json` → 运行 `merge_holdout.py`（自动去重 + 训练集泄漏检查）。

真机 wav 采集说明：`tools/nlu/holdout/README.md`

## Downgrade path

Set `encoder_config.json` `model_type` to `hash` and replace ONNX with the legacy linear model from git history if needed on weaker devices.
