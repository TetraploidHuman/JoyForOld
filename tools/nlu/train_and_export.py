#!/usr/bin/env python3
"""Fine-tune Chinese MiniLM-class encoder for offline intent routing and export INT8 ONNX.

Product scope: simple, imperative system commands when offline or for low-latency routing.
Ambiguous / Q&A utterances should classify as `none` and fall through to DeepSeek online.
Do not optimize hold-out to 100% by paraphrasing test cases into TEMPLATES.

Base model: uer/chinese_roberta_L-4_H-256 (4L RoBERTa-Mini, Chinese, ~8.8M params, INT8 ONNX ~12MB)
Outputs to app/src/main/assets/nlu/

Usage:
  pip install -r tools/nlu/requirements.txt
  python tools/nlu/train_and_export.py
"""
from __future__ import annotations

import json
import random
import shutil
import sys
from pathlib import Path

import numpy as np
from datasets import Dataset
from sklearn.metrics import accuracy_score, f1_score
from sklearn.model_selection import train_test_split
from transformers import (
    AutoModelForSequenceClassification,
    AutoTokenizer,
    DataCollatorWithPadding,
    Trainer,
    TrainingArguments,
)

try:
    from onnxruntime.quantization import QuantType, quantize_dynamic
except ImportError:
    quantize_dynamic = None

# Chinese RoBERTa-Mini: 4L / 256d (~8.8M params, INT8 ONNX ~12MB)
BASE_MODEL = "uer/chinese_roberta_L-4_H-256"
MAX_ONNX_MB = 30
MAX_LENGTH = 64
SEED = 42

OUT_DIR = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "nlu"
WORK_DIR = Path(__file__).resolve().parent / ".work"
NLU_DIR = Path(__file__).resolve().parent
if str(NLU_DIR) not in sys.path:
    sys.path.insert(0, str(NLU_DIR))

from data_generation import (  # noqa: E402
    expand_with_noise,
    generate_compositional,
    generate_confusable,
    generate_none_compositional,
)

INTENTS = [
    "open_wifi_settings",
    "open_bluetooth_settings",
    "open_sound_settings",
    "open_mobile_data_settings",
    "open_location_settings",
    "open_display_settings",
    "open_settings",
    "open_app",
    "set_alarm",
    "add_calendar_event",
    "open_camera",
    "open_gallery",
    "open_weather",
    "tell_time",
    "query_weather",
    "dial_contact",
    "navigate_home",
    "emergency_help",
    "ask_family_for_help",
    "open_health_code",
    "open_payment_code",
    "none",
]

TEMPLATES: dict[str, list[str]] = {
    "open_wifi_settings": [
        "打开wifi", "打开无线网", "打开无线网络", "开一下wifi", "帮我把wifi打开",
        "无线网设置", "wlan设置", "连接wifi", "连一下无线网", "帮我连无线网",
        "无线网络怎么开", "开无线网", "wifi在哪开",
        "把上网开关打开", "无线图标是灰的", "连不上路由器", "帮我把网打开",
        "扇形图标点开", "wifi扇形图标",
    ],
    "open_bluetooth_settings": [
        "打开蓝牙", "开蓝牙", "蓝牙设置", "帮我把蓝牙打开", "连接蓝牙", "开一下蓝牙",
        "蓝牙怎么开", "帮我开蓝牙", "连蓝牙",
        "蓝牙没开", "配对蓝牙", "搜不到蓝牙设备", "把蓝牙弄开",
    ],
    "open_sound_settings": [
        "打开声音设置", "音量设置", "调音量", "声音太大了", "媒体音量", "音量调大",
        "铃声音量", "把声音调小一点",
        "听不见声音", "静音怎么关", "通话音量", "喇叭声音太小",
        "视频通话听不见", "对方听不见我说话",
    ],
    "open_mobile_data_settings": [
        "打开移动数据", "流量设置", "数据流量", "蜂窝数据", "上网设置", "开流量",
        "移动网络设置", "开蜂窝数据", "把数据流量打开", "4g网络打开", "用流量上网",
        "上网卡开关打开", "sim卡流量打开",
    ],
    "open_location_settings": [
        "打开定位", "定位设置", "gps设置", "位置权限设置", "开定位", "位置服务",
        "打开gps", "定位不准", "开位置权限", "卫星定位",
    ],
    "open_display_settings": [
        "打开显示设置", "屏幕设置", "亮度设置", "字体显示设置", "调亮度", "屏幕亮度",
        "屏幕太暗", "字体太小", "夜间模式设置", "自动亮度",
    ],
    "open_settings": [
        "打开设置", "系统设置", "手机设置", "进入设置", "去设置", "打开系统设置",
        "齿轮图标", "系统菜单", "控制面板", "手机参数设置", "改语言设置",
        "恢复出厂设置", "进设置界面", "配置项在哪",
    ],
    "open_app": [
        "打开微信", "启动抖音", "运行支付宝", "进入淘宝", "打开高德地图",
        "打开QQ", "打开京东", "打开拼多多", "打开快手", "启动微信",
        "帮我打开微信", "开一下抖音", "上微信", "用支付宝", "开淘宝",
    ],
    "set_alarm": [
        "设闹钟7:30", "明天早上7点叫我", "明早七点闹钟", "提醒我19:30吃药",
        "下午3点叫我", "晚上八点半闹钟", "七点叫我起床", "定个七点的闹钟",
        "帮我设闹钟", "明早六点叫我",
        "定闹铃", "设个铃", "几点叫我", "闹钟提醒", "到点叫我",
        "下午两点叫我去银行", "两点提醒我去办事", "到点提醒出门",
    ],
    "add_calendar_event": [
        "明天下午三点提醒开会", "记一下周五体检", "添加日程买菜",
        "日历提醒明天交电费", "后天上午十点去医院", "安排明天开会",
        "记日程", "加进日历", "安排日程", "记个事", "写进日程表",
        "接孙子放学", "社区活动别忘了",
        "每天提醒浇水", "每天给花浇水", "重复日程提醒", "交燃气费记日历",
    ],
    "open_camera": [
        "打开相机", "拍照", "我要拍照", "开相机", "帮我拍照",
        "开镜头", "开摄像头", "拍个照", "录个视频", "拍身份证",
        "照相机打开", "帮我拍一张",
        "拍照片发给别人", "拍照发给儿子", "现在拍一张",
        "开镜头扫一扫", "扫码开摄像头", "拍个照再发",
        "我要照相", "启动拍照功能",
    ],
    "open_gallery": [
        "打开相册", "看看照片", "相册", "看照片",
        "图库", "找照片", "看截图", "照片在哪", "打开图库",
        "看以前的照片", "昨天拍的照片", "找一下截图",
        "图库里找照片", "看旧照片", "删照片去相册",
        "以前拍的花在哪看", "翻看照片",
    ],
    "open_weather": [
        "打开天气应用", "启动天气软件", "进天气程序", "打开天气预报",
        "把天气app打开", "气象软件打开", "天气应用打开", "进入天气app",
        "看一周预报进天气", "看未来温度打开天气", "滑动看预报开天气",
    ],
    "tell_time": ["几点了", "现在几点", "报时", "现在几点钟了", "什么时间", "现在几点钟", "报个时"],
    "query_weather": [
        "今天天气怎么样", "查天气", "北京天气", "帮我看天气", "今天冷不冷",
        "会不会下雨", "明天下雪吗", "热不热", "要不要带伞", "几度",
        "紫外线强吗", "出门穿啥", "降不降温",
    ],
    "dial_contact": [
        "给女儿打电话", "拨打儿子", "打电话给老伴", "联系女儿", "打给孙子",
        "帮我打电话给女儿", "拨电话", "打个电话", "给闺女拨号",
        "给儿子打视频", "视频通话打给老伴", "跟女儿视频电话",
    ],
    "navigate_home": [
        "导航回家", "带我回家", "回家路线", "导航到家里",
        "导回家", "回小区导航", "走导航回家", "回家怎么走",
    ],
    "emergency_help": [
        "紧急呼救", "救命", "sos", "快救命", "紧急求助",
        "我不行了", "快帮我叫人", "胸口疼", "打120", "摔倒了",
    ],
    "ask_family_for_help": [
        "叫家人帮忙", "联系家人帮忙", "找家人来帮忙",
        "让儿女过来", "喊家人来", "叫孩子来", "让儿子回来",
        "通知家人来", "亲属来搭把手", "让家人来一趟",
    ],
    "open_health_code": [
        "打开健康码", "健康码", "出示健康码",
        "绿码", "场所码", "防疫码", "核酸码", "行程码", "亮绿码", "扫码绿码",
    ],
    "open_payment_code": [
        "打开付款码", "付款码", "收款码", "扫我付款码",
        "扫码付钱", "出示付款码", "收银扫码", "亮付款码",
        "小卖部扫码付款", "结账用付款码", "哪个是付款码",
    ],
}

NEGATIVE_SAMPLES = [
    "你好", "谢谢", "今天吃什么", "帮我写一首诗", "打开浏览器搜索新闻",
    "我想听首歌", "发送消息给张三", "点击确认按钮", "返回上一页",
    "你在吗", "辛苦了", "好的", "不用了",
    "今天腿酸", "孙子几时放假", "帮我念微信消息", "放段京剧",
    "搜个菜谱", "微信支付咋用", "手机老重启", "没听清再说一遍",
    "血压偏高", "面条还是饺子", "算算一百减三十", "这字怎么读",
    "穿啥衣服", "重复上一句", "屏幕卡了", "相册权限啥意思",
    "闹钟咋关", "附近哪有药店", "讲个笑话", "微信咋加好友",
    "心情不错", "算了取消", "来段新闻", "播放音乐",
    "今天心情咋样", "帮我读短信", "这个字不认识", "晚上吃啥好",
    "手机发热正常吗", "帮我翻译这句话", "讲个故事", "今天星期几",
    "你会下棋吗", "帮我记个数", "我想聊天", "刚才说啥来着",
    "屏幕锁了怎么办", "内存满了咋办", "电池为啥掉得快",
    "降温穿啥合适", "怎么关闹钟", "支付宝咋用",
]

INTENT_DUP_RATES: dict[str, float] = {
    "open_app": 0.32,
}

# ASR 同音/近音变体（hold-out 反哺，用于训练增强）
ASR_HOMOPHONE_PAIRS: list[tuple[str, str]] = [
    ("无线网", "无闲网"),
    ("无线网络", "无闲网络"),
    ("蓝牙", "蓝颜"),
    ("蓝牙", "兰牙"),
    ("微信", "威信"),
    ("设置", "设制"),
    ("移动网络", "移动王络"),
    ("健康码", "健康马"),
    ("付款码", "付宽码"),
    ("抖音", "抖阴"),
    ("定位", "订位"),
    ("音量", "因量"),
    ("相机", "照相"),
    ("紧急呼救", "紧集呼救"),
    ("天气应用", "天气应佣"),
    ("老伴", "老班"),
]

PREFIXES = ["", "帮我", "请", "麻烦", "现在"]
SUFFIXES = ["", "吧", "好吗", "行不行"]
NONE_DUP_RATE = 0.55
INTENT_DUP_RATE = 0.25


def _load_holdout_texts() -> set[str]:
    holdout_path = Path(__file__).resolve().parent / "holdout" / "holdout.json"
    if not holdout_path.exists():
        return set()
    data = json.loads(holdout_path.read_text(encoding="utf-8"))
    return {s["text"].strip() for s in data.get("samples", [])}


def _asr_variants(text: str) -> set[str]:
    variants = {text}
    for src, dst in ASR_HOMOPHONE_PAIRS:
        if src in text:
            variants.add(text.replace(src, dst, 1))
    return variants


def augment_dataset() -> tuple[list[str], list[int]]:
    texts: list[str] = []
    labels: list[int] = []
    rng = random.Random(SEED)
    holdout_texts = _load_holdout_texts()
    skipped_holdout = 0

    def add_sample(text: str, intent_idx: int, dup_rate: float) -> None:
        nonlocal skipped_holdout
        t = text.strip()
        if not t:
            return
        if t in holdout_texts:
            skipped_holdout += 1
            return
        texts.append(t)
        labels.append(intent_idx)
        if rng.random() < dup_rate:
            texts.append(t)
            labels.append(intent_idx)

    intent_to_idx = {name: i for i, name in enumerate(INTENTS)}

    for intent_idx, intent in enumerate(INTENTS):
        if intent == "none":
            continue
        base = TEMPLATES.get(intent, [])
        for sample in base:
            variants = {sample}
            for prefix in PREFIXES:
                for suffix in SUFFIXES:
                    variants.add(f"{prefix}{sample}{suffix}".strip())
            if "打开" in sample:
                variants.add(sample.replace("打开", "开一下", 1))
                variants.add(sample.replace("打开", "开启", 1))
            expanded: set[str] = set()
            for text in variants:
                expanded |= _asr_variants(text)
            for text in expanded:
                dup = INTENT_DUP_RATES.get(intent, INTENT_DUP_RATE)
                add_sample(text, intent_idx, dup)

    # Compositional + confusable + ASR noise (generalization-oriented, not hold-out copies)
    compositional = generate_compositional(INTENTS, rng, per_intent=45)
    compositional = expand_with_noise(compositional, rng, noise_rate=0.30)
    for text, intent in compositional:
        add_sample(text, intent_to_idx[intent], INTENT_DUP_RATE)

    for text, intent in generate_confusable():
        add_sample(text, intent_to_idx[intent], 0.35)

    none_composed = generate_none_compositional(rng, count=100)
    none_composed = expand_with_noise(none_composed, rng, noise_rate=0.20)
    none_idx = INTENTS.index("none")
    for text, _ in none_composed:
        add_sample(text, none_idx, NONE_DUP_RATE)

    for neg in NEGATIVE_SAMPLES:
        variants = {neg}
        for prefix in PREFIXES:
            if prefix:
                variants.add(f"{prefix}{neg}")
        for text in variants:
            add_sample(text, none_idx, NONE_DUP_RATE)

    if skipped_holdout:
        print(f"Skipped {skipped_holdout} augmented samples that match hold-out text")
    return texts, labels


def export_onnx_fp32(model_dir: Path, onnx_path: Path) -> None:
    import torch
    from transformers import AutoModelForSequenceClassification

    model = AutoModelForSequenceClassification.from_pretrained(model_dir)
    model.eval()
    tokenizer = AutoTokenizer.from_pretrained(model_dir)

    dummy = tokenizer("打开蓝牙", return_tensors="pt", max_length=MAX_LENGTH, truncation=True, padding="max_length")
    input_ids = dummy["input_ids"]
    attention_mask = dummy["attention_mask"]

    torch.onnx.export(
        model,
        (input_ids, attention_mask),
        str(onnx_path),
        input_names=["input_ids", "attention_mask"],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "sequence"},
            "attention_mask": {0: "batch", 1: "sequence"},
            "logits": {0: "batch"},
        },
        opset_version=18,
        dynamo=False,
    )


def export_assets(model_dir: Path, base_model: str = BASE_MODEL, work_dir: Path = WORK_DIR) -> None:
    """Export ONNX + copy configs to app assets from a saved fine-tuned model."""
    fp32_onnx = work_dir / "intent_classifier.fp32.onnx"
    int8_onnx = work_dir / "intent_classifier.onnx"

    tokenizer = AutoTokenizer.from_pretrained(model_dir)

    print("Exporting ONNX...")
    export_onnx_fp32(model_dir, fp32_onnx)

    if quantize_dynamic is not None:
        print("Quantizing to INT8...")
        quantize_dynamic(
            str(fp32_onnx),
            str(int8_onnx),
            weight_type=QuantType.QUInt8,
        )
    else:
        shutil.copy(fp32_onnx, int8_onnx)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copy(int8_onnx, OUT_DIR / "intent_classifier.onnx")
    (OUT_DIR / "intent_labels.json").write_text(
        json.dumps(INTENTS, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    config = {
        "model_type": "transformer",
        "base_model": base_model,
        "max_length": MAX_LENGTH,
        "cls_token_id": tokenizer.cls_token_id,
        "sep_token_id": tokenizer.sep_token_id,
        "pad_token_id": tokenizer.pad_token_id,
        "unk_token_id": tokenizer.unk_token_id,
        "unk_token": tokenizer.unk_token,
        "auto_execute_threshold": 0.88,
        "clarify_threshold": 0.75,
        "margin_threshold": 0.28,
        "version": 4,
    }
    (OUT_DIR / "encoder_config.json").write_text(
        json.dumps(config, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    vocab_file = model_dir / "vocab.txt"
    if vocab_file.exists():
        shutil.copy(vocab_file, OUT_DIR / "vocab.txt")
    else:
        vocab_lines = []
        for token, _idx in sorted(tokenizer.get_vocab().items(), key=lambda x: x[1]):
            vocab_lines.append(token)
        (OUT_DIR / "vocab.txt").write_text("\n".join(vocab_lines) + "\n", encoding="utf-8")

    samples = ["打开蓝牙", "几点了", "你好", "打开微信"]
    vectors = []
    for sample in samples:
        encoded = tokenizer(sample, max_length=MAX_LENGTH, truncation=True, padding="max_length")
        vectors.append(
            {
                "text": sample,
                "input_ids": encoded["input_ids"],
                "attention_mask": encoded["attention_mask"],
            }
        )
    (OUT_DIR / "tokenizer_regression.json").write_text(
        json.dumps(vectors, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    size_mb = (OUT_DIR / "intent_classifier.onnx").stat().st_size / (1024 * 1024)
    print(f"Done. ONNX size: {size_mb:.2f} MB")
    if size_mb > MAX_ONNX_MB:
        print(f"WARNING: ONNX exceeds {MAX_ONNX_MB} MB budget — pick a smaller BASE_MODEL.")
    print(f"Assets written to {OUT_DIR}")


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--export-only",
        action="store_true",
        help="Skip training; export ONNX/assets from tools/nlu/.work/finetuned",
    )
    parser.add_argument(
        "--base-model",
        default=None,
        help="Override BASE_MODEL (e.g. hfl/rbtl3 for 1024d comparison)",
    )
    parser.add_argument(
        "--tag",
        default="default",
        help="Work subdir under tools/nlu/.work/<tag>/finetuned (avoids overwriting)",
    )
    parser.add_argument(
        "--no-export-assets",
        action="store_true",
        help="Train only; do not copy ONNX into app/src/main/assets/nlu",
    )
    args = parser.parse_args()

    base_model = args.base_model or BASE_MODEL
    work_dir = WORK_DIR / args.tag if args.tag != "default" else WORK_DIR
    model_dir = work_dir / "finetuned"
    if args.export_only:
        if not model_dir.exists():
            raise SystemExit(f"Missing fine-tuned model at {model_dir}. Run full training first.")
        export_assets(model_dir, base_model=base_model, work_dir=work_dir)
        return

    random.seed(SEED)
    np.random.seed(SEED)

    texts, labels = augment_dataset()
    train_texts, eval_texts, train_labels, eval_labels = train_test_split(
        texts, labels, test_size=0.12, random_state=SEED, stratify=labels,
    )

    tokenizer = AutoTokenizer.from_pretrained(base_model)
    model = AutoModelForSequenceClassification.from_pretrained(
        base_model,
        num_labels=len(INTENTS),
        id2label={i: label for i, label in enumerate(INTENTS)},
        label2id={label: i for i, label in enumerate(INTENTS)},
    )

    def tokenize_batch(batch):
        return tokenizer(batch["text"], truncation=True, max_length=MAX_LENGTH)

    train_ds = Dataset.from_dict({"text": train_texts, "label": train_labels}).map(tokenize_batch, batched=True)
    eval_ds = Dataset.from_dict({"text": eval_texts, "label": eval_labels}).map(tokenize_batch, batched=True)

    work_dir.mkdir(parents=True, exist_ok=True)

    from torch.nn import CrossEntropyLoss
    from transformers import EarlyStoppingCallback

    class LabelSmoothingTrainer(Trainer):
        def __init__(self, *args, label_smoothing: float = 0.08, **kwargs):
            super().__init__(*args, **kwargs)
            self.label_smoothing = label_smoothing

        def compute_loss(self, model, inputs, return_outputs=False, **kwargs):
            labels = inputs.pop("labels")
            outputs = model(**inputs)
            loss = CrossEntropyLoss(label_smoothing=self.label_smoothing)(
                outputs.logits,
                labels,
            )
            return (loss, outputs) if return_outputs else loss

    train_args = TrainingArguments(
        output_dir=str(work_dir / "checkpoints"),
        num_train_epochs=8,
        per_device_train_batch_size=16,
        per_device_eval_batch_size=16,
        learning_rate=3e-5,
        weight_decay=0.02,
        eval_strategy="epoch",
        save_strategy="epoch",
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss",
        greater_is_better=False,
        save_total_limit=1,
        logging_steps=20,
        report_to=[],
    )

    def compute_metrics(eval_pred):
        preds = np.argmax(eval_pred.predictions, axis=1)
        return {
            "accuracy": accuracy_score(eval_pred.label_ids, preds),
            "f1_macro": f1_score(eval_pred.label_ids, preds, average="macro"),
        }

    trainer = LabelSmoothingTrainer(
        model=model,
        args=train_args,
        train_dataset=train_ds,
        eval_dataset=eval_ds,
        processing_class=tokenizer,
        data_collator=DataCollatorWithPadding(tokenizer=tokenizer),
        compute_metrics=compute_metrics,
        callbacks=[EarlyStoppingCallback(early_stopping_patience=2)],
        label_smoothing=0.08,
    )

    print("Training MiniLM-class intent model...")
    trainer.train()
    metrics = trainer.evaluate()
    print(f"Eval metrics: {metrics}")

    model.save_pretrained(model_dir)
    tokenizer.save_pretrained(model_dir)
    if not args.no_export_assets:
        export_assets(model_dir, base_model=base_model, work_dir=work_dir)


if __name__ == "__main__":
    main()
