# JoyForOld

面向老年用户的 Android 语音 + AI 操控助手。通过无障碍服务观察屏幕、执行点击/输入/滚动等操作，在关键步骤（发送消息、拨打电话等）会语音询问确认。

## 环境要求

- Android Studio（或 JDK 11+）
- Android SDK（`compileSdk 36`，`minSdk 28`）
- 真机调试（需开启无障碍服务与悬浮窗权限）

## 快速开始

1. 克隆仓库：

```bash
git clone https://github.com/TetraploidHuman/JoyForOld.git
cd JoyForOld
```

2. 配置 API 密钥：

```bash
copy local.properties.example local.properties   # Windows
# cp local.properties.example local.properties # macOS / Linux
```

编辑 `local.properties`，填入你自己的密钥（**不要提交此文件**）。**`sdk.dir` 无需填写**，Gradle 会自动检测 Android SDK（环境变量 `ANDROID_SDK_ROOT` / `ANDROID_HOME`，或系统默认安装路径）。

| 配置项 | 说明 |
|--------|------|
| `deepseek.api.key` | [DeepSeek](https://platform.deepseek.com/) API Key |
| `deepseek.model` | 模型名，默认 `deepseek-v4-flash` |
| `volc.asr.api_key` | 火山引擎豆包 ASR 新版鉴权（与下方旧版二选一） |
| `volc.asr.app_id` / `volc.asr.access_token` | 豆包 ASR 旧版鉴权 |
| `volc.asr.resource_id` | ASR 资源 ID，按控制台开通的模型填写 |

也可在 App 内「API 配置」区域填写并保存 DeepSeek / 豆包语音识别密钥（存于本机 SharedPreferences，不会随仓库分发）。

3. 构建并安装：

```bash
./gradlew :app:assembleDebug
```

4. 在手机上：开启无障碍服务 → 悬浮窗权限 → 启动悬浮助手，即可语音或文字下达指令。

## 项目结构（简要）

- `agent/` — AI 步进循环、工具注册、会话记忆、敏感操作守卫
- `accessibility/` — 无障碍服务，执行 UI 操作
- `speech/` — 豆包流式语音识别
- `overlay/` — 悬浮窗助手

## 安全说明

- `local.properties`、`.gradle/`、`build/` 已在 `.gitignore` 中
- 备份规则已排除 `joy_for_old_prefs.xml`（含本机保存的 API Key）
- 克隆后请使用自己的 DeepSeek / 火山引擎账号密钥

## License

暂未指定开源协议，使用前请联系作者。
