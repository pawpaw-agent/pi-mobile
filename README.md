# pi-mobile

**手机端远程使用 [pi](https://github.com/agegr/pi) 编码助手的 Android 全屏 WebView 壳。**

连接你笔记本上运行的 [pi-web](https://github.com/agegr/pi-web) 服务，在手机上获得与桌面一致的 pi 编程体验。pi-mobile 本身只是一个极简的 WebView 容器——不做任何 UI 重写，不代理你的代码或对话，所有 AI 能力都由你自己的 pi 实例提供。

```
┌─────────────────────────────┐
│        pi-mobile            │
│  (Android WebView 壳)       │
└──────────────┬──────────────┘
               │  HTTP (WebView 加载页面)
               │  局域网 / Tailscale / 隧道
               ▼
┌─────────────────────────────┐
│        pi-web               │
│  (运行在你的笔记本 / VPS)   │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│   pi (AI 编码 Agent)        │
│   你自己的 key，你自己的账  │
└─────────────────────────────┘
```

---

## 功能

- **全屏沉浸 WebView** — `IMMERSIVE_STICKY` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，WebView 铺满整块屏幕（包括状态栏后方），在三星等机型也能画到刘海后面
- **连接屏** — 输入 host:port 即可连接 pi-web，默认端口 `30141`
- **自动重连** — 上次连接的 URL 存入 SharedPreferences，下次打开 App 直接加载，无需重复输入
- **安全 WebView 配置** — 关闭 `allowFileAccess` / `allowContentAccess`，开启 `domStorage`，自定义 UA `PiMobile/1.0`
- **明文 HTTP 支持** — `usesCleartextTraffic="true"`，方便局域网直连

> 注：当前版本不含 SSE 通知 / 后台推送 / 多连接管理。需要这些功能可在后续版本中加回。

---

## 快速开始

### 1. 在笔记本上启动 pi-web

确保 pi-web 正在运行并监听可访问的地址（例如 `0.0.0.0:30141`）。具体启动方式见 [pi-web 文档](https://github.com/agegr/pi-web)。

### 2. 安装 pi-mobile

```bash
cd android && ./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. 连接

打开 App，在连接屏输入：
- **Host** — 笔记本的局域网 IP（如 `192.168.1.100`）或 Tailscale IP（如 `100.x.x.x`）或隧道域名
- **Port** — pi-web 端口，默认 `30141`

点 **Connect**，WebView 全屏加载 pi-web，开始使用。

---

## 连接方式

| 场景 | Host 示例 | 说明 |
|---|---|---|
| 局域网 | `192.168.1.100` | 同一 WiFi 下直连，最简单 |
| Tailscale | `100.x.x.x` | 跨网络、端到端加密，推荐远程使用 |
| 隧道 | `my-pi.trycloudflare.com` | Cloudflare Tunnel / ngrok，端口填 443，注意当前连接屏固定拼 `http://`，HTTPS 隧道需自行改代码 |

> 当前连接屏固定生成 `http://host:port`，HTTPS 隧道需要小改 `MainActivity.kt` 里的 URL 拼接逻辑。

---

## 项目结构

```
pi-mobile/
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/pimobile/app/
│   │   │   │   └── MainActivity.kt      # 连接屏 + 全屏 WebView（约 200 行）
│   │   │   ├── AndroidManifest.xml
│   │   │   └── res/                     # 启动图标 + 主题
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle/wrapper/
├── scripts/
│   └── build-apk.sh                     # 封装 gradle assembleDebug
├── package.json
└── README.md
```

整个 App 就是一个 `MainActivity.kt`，没有 Fragment / Compose / DI / 网络层——pi-web 升级时无需改 App。

---

## 技术栈

| 项 | 值 |
|---|---|
| 语言 | Kotlin |
| 最低 Android | 8.0（API 26） |
| 目标 Android | 14（API 34） |
| 构建 | Gradle 8.5 + AGP 8.2.2 |
| 关键依赖 | `androidx.webkit:webkit:1.9.0`、`androidx.core:core-ktx:1.12.0` |
| 应用 ID | `com.pimobile.app` |
| 版本 | 1.0.0 |

---

## 构建

需要本地配置 Android SDK（`android/local.properties` 里写 `sdk.dir=...`，该文件被 gitignore）。

```bash
# 方式一：脚本封装
sh scripts/build-apk.sh

# 方式二：直接 gradle
cd android && ./gradlew assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

---

## 许可

Apache 2.0
