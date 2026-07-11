"""Composable training data generation — diversity without hold-out paraphrase leakage."""
from __future__ import annotations

import random
import re
from typing import Iterable

# Slot patterns: Cartesian product of fillers → utterances (NOT hold-out copies).
COMPOSITIONAL: dict[str, list[str]] = {
    "open_wifi_settings": [
        "{v}{n}设置",
        "{v}一下{n}",
        "帮我把{n}{v}",
        "{n}连不上{v}",
        "无线网{n}图标是灰的",
        "路由器搜不到{n}",
        "家里{n}怎么开",
    ],
    "open_bluetooth_settings": [
        "{v}{n}",
        "帮我把{n}{v}",
        "{n}没开",
        "耳机连不上{v}",
        "重新{v}{n}",
        "搜不到蓝牙设备",
    ],
    "open_sound_settings": [
        "{v}声音设置",
        "调{v}音量",
        "听不见{v}",
        "通话{v}太小",
        "铃声音量{v}",
        "媒体声音{v}一点",
    ],
    "open_mobile_data_settings": [
        "{v}{n}",
        "帮我把{n}{v}",
        "{n}关着上不了网",
        "没WiFi用{n}上网",
        "SIM卡{n}{v}",
        "4G上不了开{n}",
        "出门用{n}上网",
    ],
    "open_location_settings": [
        "{v}定位",
        "{v}GPS",
        "位置服务{v}",
        "定位不准{v}",
        "卫星定位{v}",
    ],
    "open_display_settings": [
        "{v}显示设置",
        "屏幕{v}一点",
        "字体{v}",
        "夜间模式{v}",
        "屏幕{v}看不清",
    ],
    "open_settings": [
        "{v}设置",
        "进{v}设置",
        "系统{v}在哪",
        "齿轮图标{v}",
        "手机参数{v}",
    ],
    "open_app": [
        "{v}{app}",
        "帮我{v}{app}",
        "上{app}{purpose}",
        "{purpose}用{app}",
    ],
    "set_alarm": [
        "{time}叫我",
        "设闹钟{time}",
        "{time}提醒{task}",
        "定个{time}的闹铃",
        "{delta}后提醒{task}",
    ],
    "add_calendar_event": [
        "{when}提醒{event}",
        "记一下{when}{event}",
        "把{event}加进日历",
        "{when}去{place}记日程",
        "每天{time}提醒{event}",
    ],
    "open_camera": [
        "{v}相机",
        "我要{shot}",
        "{v}摄像头",
        "现在{shot}",
        "扫码不行{v}镜头",
    ],
    "open_gallery": [
        "{v}相册",
        "看{when}的{subject}",
        "图库里找{subject}",
        "翻{when}拍的照片",
    ],
    "open_weather": [
        "{v}天气应用",
        "进天气{v}",
        "看预报{v}天气",
    ],
    "tell_time": [
        "现在几点",
        "报个时",
        "几点钟了",
        "什么时间",
    ],
    "query_weather": [
        "{city}天气怎么样",
        "今天{feel}",
        "明天{feel}",
        "要不要带伞",
        "查一下天气",
    ],
    "dial_contact": [
        "给{who}打电话",
        "拨打{who}",
        "跟{who}视频",
        "联系{who}",
    ],
    "navigate_home": [
        "导航回家",
        "带我回{place}",
        "从{place}回家怎么走",
        "导回{place}",
    ],
    "emergency_help": [
        "救命",
        "快打120",
        "我不行了",
        "胸口疼",
        "燃气泄漏了",
        "闻到煤气味",
    ],
    "ask_family_for_help": [
        "叫家人来",
        "让{who}过来",
        "联系家人帮忙",
        "通知{who}来一趟",
    ],
    "open_health_code": [
        "出示健康码",
        "亮{code}",
        "打开{code}",
        "扫码{code}",
    ],
    "open_payment_code": [
        "出示付款码",
        "亮付款码",
        "{place}结账用哪个码",
        "扫码付钱",
    ],
}

SLOTS: dict[str, list[str]] = {
    "v": ["打开", "开启", "启动", "开一下", "弄开", "启用"],
    "n": ["WiFi", "无线网", "WLAN", "蓝牙", "移动数据", "流量", "蜂窝网络"],
    "v_sound": ["调大", "调小", "加大", "减小"],
    "app": ["微信", "抖音", "支付宝", "淘宝", "京东", "QQ", "快手", "高德地图"],
    "purpose": ["聊天", "缴费", "购物", "查快递", "刷视频", "叫车"],
    "time": ["7点", "8点半", "下午3点", "明早6点", "晚上9点"],
    "delta": ["10分钟", "半小时", "5分钟"],
    "task": ["吃药", "关火", "出门", "锻炼"],
    "when": ["明天", "后天", "下周五", "下周三"],
    "event": ["开会", "体检", "买菜", "交水费", "接孩子"],
    "place": ["银行", "医院", "社区", "家里", "公司"],
    "shot": ["拍照", "拍一张", "照相", "录视频"],
    "subject": ["照片", "截图", "视频", "风景"],
    "city": ["北京", "上海", "广州", "杭州"],
    "feel": ["冷不冷", "热不热", "下不下雨", "要不要添衣"],
    "who": ["女儿", "儿子", "老伴", "孙子", "孩子"],
    "code": ["绿码", "场所码", "防疫码"],
}

# Intent-specific slot overrides (avoid cross-intent slot pollution).
INTENT_SLOTS: dict[str, dict[str, list[str]]] = {
    "open_wifi_settings": {"n": ["WiFi", "无线网", "WLAN", "无线局域网"]},
    "open_bluetooth_settings": {"n": ["蓝牙"]},
    "open_mobile_data_settings": {"n": ["移动数据", "流量", "蜂窝网络", "4G"]},
    "open_sound_settings": {"v": ["调大", "调小", "加大", "减小", "打开"]},
    "open_display_settings": {"v": ["调亮", "调暗", "放大", "打开"]},
    "open_settings": {"v": ["打开", "进入", "找到"]},
    "open_camera": {"v": ["打开", "开", "启动"]},
    "open_gallery": {"v": ["打开", "看看", "翻翻"]},
    "open_weather": {"v": ["打开", "启动", "进入"]},
    "open_payment_code": {"place": ["超市", "商店", "摊位", "便利店"]},
}

# Hard contrast: same surface structure, different intent (teaches boundaries).
CONFUSABLE: list[tuple[str, str]] = [
    ("打开WiFi用无线网上网", "open_wifi_settings"),
    ("打开WiFi无线设置", "open_wifi_settings"),
    ("打开流量用移动数据上网", "open_mobile_data_settings"),
    ("打开移动数据流量设置", "open_mobile_data_settings"),
    ("没WiFi帮我开蜂窝数据", "open_mobile_data_settings"),
    ("连路由器开无线网", "open_wifi_settings"),
    ("现在拍张照", "open_camera"),
    ("看上次拍的照片", "open_gallery"),
    ("我要照相", "open_camera"),
    ("找相册里的旧图", "open_gallery"),
    ("出示绿码", "open_health_code"),
    ("出示付款码结账", "open_payment_code"),
    ("超市扫我付款码", "open_payment_code"),
    ("亮健康码", "open_health_code"),
    ("明早7点叫我起床", "set_alarm"),
    ("明早7点记体检日程", "add_calendar_event"),
    ("十分钟后叫我", "set_alarm"),
    ("十分钟后提醒交电费", "add_calendar_event"),
    ("打开设置", "open_settings"),
    ("打开声音设置", "open_sound_settings"),
    ("打开微信", "open_app"),
    ("今天天气怎么样", "query_weather"),
    ("打开天气应用", "open_weather"),
    ("闻到燃气泄漏", "emergency_help"),
    ("记得交燃气费", "add_calendar_event"),
]

ASR_HOMOPHONES: list[tuple[str, str]] = [
    ("无线", "无闲"), ("蓝牙", "蓝颜"), ("微信", "威信"), ("设置", "设制"),
    ("流量", "流亮"), ("健康", "建康"), ("付款", "付宽"), ("相机", "相鸡"),
    ("闹钟", "闹种"), ("导航", "倒航"), ("天气", "天汽"), ("支付宝", "支富宝"),
]

FILLERS = ["", "嗯", "那个", "帮我", "请"]

# Chit-chat negatives (compositional).
NONE_PATTERNS = [
    "今天{topic}",
    "帮我{ask}",
    "{topic}怎么办",
    "我想{want}",
    "播放{media}",
    "搜一下{topic}",
]

NONE_SLOTS = {
    "topic": ["吃什么", "腿酸", "心情", "股票", "新闻"],
    "ask": ["写首诗", "算个数", "翻译", "读短信"],
    "want": ["听歌", "聊天", "下棋"],
    "media": ["音乐", "京剧", "评书"],
}


def _fill_pattern(pattern: str, intent: str, rng: random.Random) -> str:
    slots = dict(SLOTS)
    slots.update(INTENT_SLOTS.get(intent, {}))
    if intent == "open_sound_settings" and "{v}" in pattern:
        slots["v"] = slots.get("v_sound", slots["v"])

    def repl(match: re.Match[str]) -> str:
        key = match.group(1)
        choices = slots.get(key, [key])
        return rng.choice(choices)

    return re.sub(r"\{(\w+)\}", repl, pattern)


def generate_compositional(
    intents: list[str],
    rng: random.Random,
    *,
    per_intent: int = 40,
) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    for intent in intents:
        if intent == "none":
            continue
        patterns = COMPOSITIONAL.get(intent, [])
        if not patterns:
            continue
        seen: set[str] = set()
        attempts = 0
        while len(seen) < per_intent and attempts < per_intent * 8:
            attempts += 1
            text = _fill_pattern(rng.choice(patterns), intent, rng)
            text = text.strip()
            if len(text) < 2 or text in seen:
                continue
            seen.add(text)
            out.append((text, intent))
    return out


def generate_none_compositional(rng: random.Random, count: int = 80) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    seen: set[str] = set()
    attempts = 0
    while len(out) < count and attempts < count * 8:
        attempts += 1
        pattern = rng.choice(NONE_PATTERNS)

        def repl(match: re.Match[str]) -> str:
            key = match.group(1)
            choices = NONE_SLOTS.get(key, [key])
            return rng.choice(choices)

        text = re.sub(r"\{(\w+)\}", repl, pattern).strip()
        if text in seen:
            continue
        seen.add(text)
        out.append((text, "none"))
    return out


def generate_confusable() -> list[tuple[str, str]]:
    return list(CONFUSABLE)


def apply_asr_noise(text: str, rng: random.Random) -> str:
    """Light ASR-style perturbation (homophone + filler)."""
    t = text
    if rng.random() < 0.35:
        for src, dst in ASR_HOMOPHONES:
            if src in t and rng.random() < 0.5:
                t = t.replace(src, dst, 1)
                break
    if rng.random() < 0.2 and not t.startswith("帮"):
        filler = rng.choice(FILLERS)
        if filler:
            t = f"{filler}{t}"
    if rng.random() < 0.1:
        t = t.replace("的", "", 1)
    return t.strip()


def expand_with_noise(
    pairs: Iterable[tuple[str, str]],
    rng: random.Random,
    *,
    noise_rate: float = 0.25,
) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    for text, intent in pairs:
        out.append((text, intent))
        if rng.random() < noise_rate:
            noisy = apply_asr_noise(text, rng)
            if noisy and noisy != text:
                out.append((noisy, intent))
    return out
