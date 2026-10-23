# 安智伴 · AI 数字人陪护终端（Android）


> 本项目与 家属监护 Web 端配合使用，共同构成完整的"AI 数字人 + 家庭远程关怀"解决方案。

---

## 目录

- [需求分析](#需求分析)
- [设计思路](#设计思路)
- [技术架构](#技术架构)
- [模块说明](#模块说明)
- [全链路数据流](#全链路数据流)
- [通信协议](#通信协议)
- [集成要求](#集成要求)
- [SDK 接口文档](#sdk-接口文档)
- [构建与运行](#构建与运行)

---

## 需求分析

### 背景

中国老龄化进程加速，独居或空巢老人的心理陪伴和安全监护成为刚需。子女因工作或地理距离无法陪伴，需要一种低门槛、有温度的技术方案来弥合亲情距离。

### 核心需求

| 需求维度 | 描述 |
|---------|------|
| **AI 陪伴对话** | 老人可随时与屏幕上的"数字人"自然语音对话，获得情感陪伴和生活助理服务 |
| **家庭留言桥梁** | 子女通过 Web 端发送关怀留言 → 数字人主动转达；老人口述留言 → 子女在 Web 端收到 |
| **使用行为上报** | 设备自动向云端上报使用事件（开屏、对话时长等），供家属了解老人日常 |
| **离线数字人渲染** | 数字人形象的 2D 渲染完全在本地完成（NCNN 推理 + OpenGL），无需 GPU 服务器 |
| **低门槛部署** | 老人无需任何操作，设备预装即可使用；家属扫码绑定即可监护 |

### 用户角色

| 角色 | 使用场景 |
|------|---------|
| **老人**（设备端） | 与数字人语音聊天、听取家人留言、口述回复 |
| **家属**（Web 端） | 发送留言、查看老人使用动态、接收推送通知 |

---

## 设计思路

### 1. 端云分离、各司其职

- **端侧（本项目）**：承担数字人渲染（完全离线）+ 实时 AI 对话（通过 Qwen Omni-Realtime 云端模型）+ 家庭消息同步
- **云侧（Monitor Web）**：承担家属身份管理、设备绑定、消息存储与分发、使用数据可视化

### 2. AI 对话与数字人解耦

DUIX SDK 仅负责"给定 PCM 音频 → 渲染对应口型/表情动画"，不关心音频来源。音频可来自：
- Qwen Omni-Realtime 模型的实时语音回复
- 本地 TTS 合成
- 任意 WAV 文件

这种解耦使得 AI 模型可独立升级，不影响渲染管线。

### 3. 工具调用 + 实现双向留言

通过 Qwen 的 Function Calling 能力，在 AI 对话中注册三个工具函数：
- `consult_psychology_knowledge_base`：Omni 提炼老人问题 → Android 端调用 qwen3.6-plus，并把 WHO ICOPE 2024 全文作为上下文 → 返回权威说明
- `leave_message_for_family`：老人口述 → AI 提取内容 → 调用工具 → 消息存入云端
- `fetch_family_messages`：老人想听留言 → AI 调用工具 → 拉取消息 → 口播转达

AI 自主判断何时调用工具，无需老人学习任何操作指令。涉及权威照护依据时，实时语音模型不会直接吞入整份知识库上下文，而是把问题转交给长上下文子代理处理。

### 4. 面向老人的交互设计

- **语义 VAD**：采用 `semantic_vad` 而非传统声学 VAD，能过滤电视背景音、咳嗽等噪音，仅在老人真正说话时响应
- **主动播报**：对话开始时若有未读家人留言，AI 会主动口播转达，无需老人主动查询
- **温和人设**：系统 Prompt 设定为"面向中国老年群体的心理陪伴 AI"，使用温和、简短、生活化的中文回复

---

## 技术架构

### 系统全景

```
┌──────────────────────────────────────────────────────────────────┐
│                        Android 设备端                             │
│                                                                  │
│  ┌─────────────┐    PCM音频    ┌──────────────────────────────┐  │
│  │ Qwen Omni   │ ────────────→ │        DUIX SDK              │  │
│  │ Realtime    │               │ ┌────────────────────────┐   │  │
│  │ (WebSocket) │    AI回复语音  │ │ NCNN 推理引擎          │   │  │
│  │             │               │ │ (口型/表情/动作 推理)   │   │  │
│  └──────┬──────┘               │ └───────────┬────────────┘   │  │
│         │                      │             │ ImageFrame     │  │
│  语音   │ 文本               │ ┌───────────▼────────────┐   │  │
│  输入   │ +语音               │ │ OpenGL ES 2.0 渲染     │   │  │
│         │                      │ │ (DUIXRenderer)         │   │  │
│  ┌──────▼──────┐               │ └───────────┬────────────┘   │  │
│  │  麦克风录音  │               │             │               │  │
│  │  (16kHz PCM) │               │ ┌───────────▼────────────┐   │  │
│  └─────────────┘               │ │ GLTextureView (UI)     │   │  │
│                                └──────────────────────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │              FamilyBridgeService (HTTP)                   │    │
│  │  • 设备注册 → POST /api/device/register                   │    │
│  │  • 拉取留言 → GET  /api/device/messages                   │    │
│  │  • 标记已读 → POST /api/device/messages/read              │    │
│  │  • 上报事件 → POST /api/device/usage                      │    │
│  │  • 对话存档 → POST /api/device/conversations              │    │
│  │  • 工具记录 → POST /api/device/tool-call                  │    │
│  └──────────────────────────────┬───────────────────────────┘    │
└─────────────────────────────────┼────────────────────────────────┘
                                  │ HTTPS
                                  ▼
┌──────────────────────────────────────────────────────────────────┐
│           digital-human-monitor-v2 (Next.js 云端)                │
│                                                                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐    │
│  │ 设备管理  │  │ 消息存储  │  │ 行为分析  │  │ Web Push 推送 │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────────┘    │
│                       │                                          │
│                       ▼                                          │
│              ┌──────────────┐                                    │
│              │  PostgreSQL   │                                    │
│              └──────────────┘                                    │
└──────────────────────────────────────────────────────────────────┘
```

### 技术栈

| 层级 | 技术 |
|------|------|
| **数字人渲染** | NCNN (端侧推理) + OpenCV 4.6 + OpenGL ES 2.0 + ONNX Runtime |
| **AI 对话** | 千问 Qwen 3.5 Omni Plus Realtime (WebSocket) + Qwen 3.6 Plus 知识子代理 (HTTP) |
| **音频处理** | AudioRecord (16kHz) → PCM → Base64 → WebSocket；AudioTrack 播放 |
| **网络通信** | OkHttp 4 (HTTP API / qwen3.6-plus) + OkHttp WebSocket (Qwen Realtime) |
| **本地存储** | SharedPreferences (设备 UUID、配置) |
| **构建系统** | Gradle 8.13.2 + AGP 8.9.1 + JDK 17 (Temurin) |
| **语言** | Kotlin (应用层) + Java (SDK 层) + C++ (Native 推理层) |

---

## 模块说明

### 项目结构

```
dh_aigc_android/
├── duix-sdk/           # 核心 2D 数字人渲染 SDK（库模块）
│   ├── src/main/java/  # Java SDK 接口层
│   │   └── ai/guiji/duix/
│   │       ├── sdk/client/         # DUIX 主控、渲染、回调、工具类
│   │       └── duix/DuixNcnn.java  # JNI 接口
│   └── src/main/cpp/   # C++ Native 推理引擎
│       ├── dhcore/     # 核心数字人推理
│       ├── dhmfcc/     # 音频 MFCC 特征提取
│       ├── dhunet/     # 口型网络模型
│       └── third/      # 预编译依赖 (NCNN, OpenCV, ONNX, libjpeg)
│
├── test/               # 演示应用（应用模块）
│   └── src/main/
│       ├── java/ai/guiji/duix/test/
│       │   ├── ui/activity/
│       │   │   ├── MainActivity.kt        # 主页：模型选择与下载管理
│       │   │   ├── CallActivity.kt        # 核心：数字人渲染 + AI 对话 + 留言桥梁 + 知识子代理
│       │   │   ├── SettingsActivity.kt     # 设备设置
│       │   │   └── VoiceCloneSetupActivity.kt  # 声音克隆配置
│       │   ├── realtime/
│       │   │   ├── OmniRealtimeClient.java   # Qwen Omni WebSocket 客户端
│       │   │   └── QwenTtsRealtimeClient.kt # Qwen TTS 客户端
│       │   ├── service/
│       │   │   ├── FamilyBridgeService.kt  # 家庭监护 API 客户端
│       │   │   ├── PsychologyKnowledgeAgentService.kt # qwen3.6-plus 长上下文知识子代理
│       │   │   ├── VoiceCloneService.kt    # 声音克隆服务
│       │   │   └── DashScopeConfig.java    # API 配置常量
│       │   ├── audio/
│       │   │   ├── AudioResampler.java     # PCM 重采样
│       │   │   └── StreamingAudioRecorder.java  # 实时麦克风录音
│       │   └── camera/
│       │       └── CameraFrameCapture.java # 摄像头帧采集
│       └── assets/knowledge/
│           └── who_icope_2024.txt      # WHO ICOPE 2024 权威知识库全文
│
└── qwen-docs/          # 千问 Omni-Realtime API 参考文档
    ├── main.md         # 协议总览
    ├── client_events.md    # 客户端事件定义
    ├── server_events.md    # 服务端事件定义
    └── function_calling.md # 工具调用机制
```

### duix-sdk — 数字人渲染引擎

| 类 | 职责 |
|----|------|
| `DUIX` | 主控对象：管理初始化、渲染循环、音频驱动、生命周期 |
| `RenderThread` | 独立渲染线程：逐帧推理 + 音频同步 |
| `DUIXRenderer` | OpenGL ES 2.0 渲染器：将 ImageFrame 渲染到 TextureView |
| `DUIXTextureView` | 自定义 TextureView：提供 GL 渲染表面 |
| `VirtualModelUtil` | 模型资源管理：下载、校验、解压 |
| `DuixNcnn` | JNI 桥接：调用 C++ 层 NCNN 推理 |

**渲染流水线**：
```
pushPcm(音频) → MFCC特征提取 → NCNN口型网络推理
→ 获取ImageFrame(图像+掩码) → OpenGL混合渲染 → TextureView显示
```

### test — 应用集成层

| 类 | 职责 |
|----|------|
| `CallActivity` | 整合 DUIX 渲染 + Qwen 对话 + 留言桥梁的核心页面 |
| `OmniRealtimeClient` | Qwen Omni-Realtime WebSocket 客户端，处理实时语音对话 |
| `FamilyBridgeService` | 家庭监护云 API 客户端，负责设备注册、消息同步、事件上报 |
| `MainActivity` | 模型选择与下载管理入口 |

---

## 全链路数据流

### 1. AI 实时对话

```
老人说话 → 麦克风录音 (16kHz PCM)
→ Base64 编码 → input_audio_buffer.append (WebSocket)
→ Qwen Omni 模型推理
→ response.audio.delta (Base64 PCM 回传)
→ 解码为 PCM → DUIX.pushPcm()
→ NCNN 推理口型动画 → OpenGL 渲染
→ 同时 AudioTrack 播放语音
```

### 2. 留言传递（老人 → 家属）

```
老人对 AI 说 "帮我给儿子带个话，说我挺好的"
→ Qwen 识别意图 → 触发 leave_message_for_family 工具
→ 提取 recipientRelation="儿子", message="我挺好的"
→ FamilyBridgeService → POST /api/device/conversations
→ Monitor Web 存入数据库
→ Web Push 通知家属
→ AI 回复老人 "好的，已经帮您告诉儿子了"
```

### 3. 留言传递（家属 → 老人）

```
家属在 Web 端发送留言 → 存入 FamilyMessage 表
→ 老人设备启动 AI 对话
→ FamilyBridgeService 拉取未读消息
→ 注入 AI 系统 Prompt: "家里人刚留了新留言，请主动转告..."
→ AI 在第一轮回复中口播: "您儿子给您捎了个话——记得吃药"
```

### 4. 使用行为上报

```
设备事件 (APP_OPEN / AI_SESSION_STARTED / TOOL_CALLED / ...)
→ FamilyBridgeService → POST /api/device/usage
→ Monitor Web 存入 UsageEvent 表
→ 家属在"陪伴动态"页面查看趋势图
```

---

## 通信协议

### Qwen Omni-Realtime WebSocket

| 方向 | 事件 | 用途 |
|------|------|------|
| C→S | `session.update` | 设置模态(text+audio)、语音、VAD、系统 Prompt、工具列表 |
| C→S | `input_audio_buffer.append` | 推送用户麦克风 PCM (Base64) |
| S→C | `response.audio.delta` | 模型语音回复增量块 (Base64 PCM) |
| S→C | `response.text.done` | 模型文本回复完成 |
| S→C | `response.function_call_arguments.done` | 工具调用请求 |
| C→S | `conversation.item.create` | 回传工具执行结果 |
| C→S | `response.create` | 触发模型基于工具结果生成最终回复 |

### 注册的 AI 工具

| 工具名 | 参数 | 用途 |
|--------|------|------|
| `consult_psychology_knowledge_base` | `question` (核心问题), `focus` (聚焦点，可选) | 调用 qwen3.6-plus + WHO ICOPE 全文，返回权威照护说明 |
| `leave_message_for_family` | `recipientRelation` (称谓), `message` (内容), `importance` (优先级) | 老人留言给家人 |
| `fetch_family_messages` | `count` (数量 1-5) | 拉取家人留言 |

### FamilyBridge HTTP API

| 端点 | 方法 | 用途 |
|------|------|------|
| `/api/device/register` | POST | 设备注册，返回 `deviceUuid` + `bindUrl` |
| `/api/device/messages` | GET | 拉取待送达消息 |
| `/api/device/messages/read` | POST | 标记消息已读 |
| `/api/device/usage` | POST | 上报使用事件 |
| `/api/device/conversations` | POST | 上传对话轮次 |
| `/api/device/tool-call` | POST | 记录工具调用日志 |

---

## 集成要求

| 项目 | 要求 |
|------|------|
| 系统版本 | Android 10+ (API 24+) |
| CPU 架构 | arm64-v8a, armeabi-v7a |
| 硬件 | 8 核 CPU (骁龙 8 Gen2), 8GB+ 内存, 1GB+ 存储 |
| 内存 | 数字人运行需 ≥ 800MB 可用内存 |
| 网络 | 数字人渲染：无需网络；AI 对话 + 监护同步：需联网 |
| 开发 IDE | Android Studio Giraffe 2022.3.1+ |
| JDK | OpenJDK 17 (Temurin) |
| Gradle | 8.13.2 (使用项目自带 wrapper) |

---

## SDK 接口文档

### 术语

| 术语 | 含义 |
|------|------|
| PCM | 16kHz 采样率、16bit 位深、Mono 单通道的原始音频流 |
| WAV | 支持 PCM 编码的音频文件格式 |
| RenderSink | 渲染数据接收接口，SDK 提供默认实现 |
| DUIX | 数字人主控对象 |
| ImageFrame | 包含 rawBuffer (图像) + maskBuffer (掩码) + 宽高的渲染帧 |

### 模型检查与下载

```kotlin
// 检查基础配置
VirtualModelUtil.checkBaseConfig(context): Boolean
// 下载基础配置
VirtualModelUtil.baseConfigDownload(context, url, callback)

// 检查模型
VirtualModelUtil.checkModel(context, modelUrl): Boolean
// 下载模型
VirtualModelUtil.modelDownload(context, modelUrl, callback)
```

### 初始化与渲染

```kotlin
// 构建 DUIX 实例
val duix = DUIX(context, modelUrl, renderSink) { event, msg, info ->
    when (event) {
        Constant.CALLBACK_EVENT_INIT_READY -> { /* 初始化完成 */ }
        Constant.CALLBACK_EVENT_INIT_ERROR -> { /* 初始化失败 */ }
        Constant.CALLBACK_EVENT_PLAY_START -> { /* 播放开始 */ }
        Constant.CALLBACK_EVENT_PLAY_END   -> { /* 播放结束 */ }
    }
}
duix.init()
```

### 音频驱动

```kotlin
// PCM 流式推送（用于实时 AI 语音）
duix.pushPcm(pcmBytes)

// WAV 文件播放
duix.playWav(wavFilePath)
```

### 资源释放

```kotlin
// 在 Activity onDestroy 中调用
duix.release()
```

---

## 构建与运行

### 环境准备

1. 安装 Android Studio Giraffe 2022.3.1+
2. 配置 JDK 17：`File → Settings → Build → Gradle → Gradle JDK → 选择 JDK 17`
3. 连接 Android 设备（需满足硬件要求）

### 编译安装

```bash
# 使用 Gradle Wrapper 编译安装 test 应用
./gradlew :test:installDebug
```

### 配置说明

API 地址和模型参数在 `DashScopeConfig.java` 中配置，DashScope API Key 不再写入源码：

```java
MONITOR_BASE_URL   // 监护 Web 服务地址
REALTIME_BASE_URL  // Qwen Omni WebSocket 地址
MULTIMODAL_GENERATION_URL // qwen3.6-plus HTTP 地址
DEFAULT_API_KEY    // 从 BuildConfig.DASHSCOPE_API_KEY 读取
PSYCHOLOGY_SUBAGENT_MODEL // 心理学知识子代理模型
PSYCHOLOGY_KNOWLEDGE_ASSET_PATH // 内置 WHO ICOPE 知识库 asset 路径
AUDIO_SAMPLE_RATE  // 音频采样率 (24000)
```

首次编译前，复制本地配置模板并填写自己的 Key：

```bash
cp secrets.properties.example secrets.properties
```

```properties
DASHSCOPE_API_KEY=你的 DashScope API Key
SIGNING_STORE_FILE=../demo.jks
SIGNING_STORE_PASSWORD=你的签名密码
SIGNING_KEY_ALIAS=demo
SIGNING_KEY_PASSWORD=你的 Key 密码
```

`secrets.properties`、`local.properties`、`demo.jks` 和 `*.keystore` 都已被 `.gitignore` 排除，不要提交真实密钥或签名文件。没有签名文件时，debug 构建会使用 Android 默认调试签名。