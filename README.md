# AI 输入法（AI IME）

一个自带中文 26 键输入和内置 AI 对话的安卓输入法。

本项目是 [LianAi Keyboard（恋爱键盘）](https://github.com/1213109176kgy-png/LianAi-Keyboard) 的修改版，
后者基于 **Vertick IME**，中文引擎来自 **Trime / librime**。

源码地址：<https://github.com/MikatoMisaka/ai-ime>

## 功能

- 中文 26 键拼音（Rime 引擎，全键盘方案）与英文输入
- AI 对话面板：配置自己的 OpenAI 兼容接口（baseURL、API Key、模型名）
- 图片作为对话上下文（最多 4 张，历史仅保留最近 2 轮图片）
- 回答先预览，点「插入」写回当前输入框
- 顶部二级工具栏：复制 / 粘贴 / 全选
- 按键音默认关闭，可在设置里开启

## 与原版的差异

- 移除订阅、会员、账号、购买与「帮你回 / 超会说」入口及相关后台客户端
- 新增 AI 对话面板（`app/src/main/java/com/weike/ime/ai/`）
- 中文键盘固定 26 键、隐藏顶部数字行、按屏宽推导键面比例
- 包名改为 `com.weike.aikeyboard`，界面文案与启动入口调整

## 构建

需要 JDK 17+、Android SDK 35、NDK 26.1.10909125、Gradle 8.9。

```bash
# 在 local.properties 写入 sdk.dir
gradle :app:assemblePerf     # 非 debuggable 的性能包（推荐）
gradle :app:assembleDebug    # 调试包
```

`perf` 与 `debug` 使用同一个调试签名，可直接覆盖安装。依赖仓库在 `settings.gradle.kts`
中使用阿里云镜像，可按需替换。

## 安装与使用

1. `adb install -r app/build/outputs/apk/perf/app-perf.apk`
2. 系统设置 → 语言与输入法 → 启用「AI 输入法」
3. 任意输入框唤起键盘，点右上角 **AI**，首次进入填写接口地址、Key 与模型名

图片理解需要视觉模型（如 `qwen-vl-max`、`gpt-4o`、`glm-4v`）。

## 许可

本项目按 **GNU General Public License v3.0 or later** 分发，全文见 [LICENSE](LICENSE)。
第三方组件与对应源码说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，
`librime_jni.so` 的完整对应源码与版本锁在 `third_party/librime/`。

修改记录与版权声明见 [NOTICE](NOTICE)。应用内「设置 → 开源许可」可查看 GPL 全文与第三方声明。

## 致谢

- [LianAi Keyboard](https://github.com/1213109176kgy-png/LianAi-Keyboard)（直接上游）
- Vertick IME
- [Trime](https://github.com/osfans/trime) 与 [librime](https://github.com/rime/librime)
- [Rime-Ice](https://github.com/iDvel/rime-ice) 词典
- [cppjieba](https://github.com/yanyiwu/cppjieba)、[Lucide](https://lucide.dev/)
