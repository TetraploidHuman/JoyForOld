# Hold-out 评测集

与 `train_and_export.py` 的 **TEMPLATES 合成训练集完全隔离**，用于衡量真实口语 / 真机 ASR 转写下的意图识别效果。

## 文件

| 文件 | 说明 |
|------|------|
| `holdout.json` | 主数据集（JSON，`samples[]`，含 `stats`） |
| `expand_samples.json` | 增量样本（合并用） |
| `merge_holdout.py` | 合并 + 训练集泄漏检查 |
| `recordings/` | 真机 wav（按 manifest 命名） |
| `recordings/manifest.template.jsonl` | 录音采集模板 |

### 扩充数据集

```bash
# 编辑 expand_samples.json 追加样本后：
python tools/nlu/holdout/merge_holdout.py
python tools/nlu/eval_holdout.py
```

## 样本字段

```json
{
  "id": "h-wifi-01",
  "text": "转写文本（人工或 ASR）",
  "intent": "open_wifi_settings",
  "source": "human | asr_device",
  "slots": { "app": "微信" },
  "recording": {
    "wav": "recordings/835/wifi_01.wav",
    "status": "done | pending",
    "device": "Snapdragon 835",
    "asr_engine": "doubao",
    "recorded_at": "2026-07-11"
  },
  "notes": "可选备注"
}
```

## 真机录音采集流程

1. 在 App 或系统录音机录 wav（16 kHz mono 推荐，与 ASR 一致）。
2. 复制到 `recordings/<device>/`，文件名 `{intent}_{序号}.wav`。
3. 用豆包/设备 ASR 转写，人工校对 `text`。
4. 在 `holdout.json` 新增或更新对应 `id`，`source` 设为 `asr_device`，`recording.status` 设为 `done`。
5. 运行泄漏检查 + 评测：

```bash
python tools/nlu/eval_holdout.py
python tools/nlu/eval_holdout.py --leak-check-only
```

## 规则

- **禁止**把 `holdout.json` 任何 `text` 加入 `TEMPLATES` / `NEGATIVE_SAMPLES`。
- 新增样本优先用口语、省略、ASR 同音错字，避免复制训练模板。
- `pending` 录音可先填 ASR 模拟文本，真机 wav 到位后替换。
