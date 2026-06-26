# bvds — B站视频下载器 (Android)

基于 Python 版 [bvds.py](bvds.py) 移植的 Android APK，WebView + Java 原生桥接架构。

## 项目结构

```
bvds/
├── build.gradle                    # 根构建脚本
├── settings.gradle
├── gradle.properties
└── app/
    ├── build.gradle                # 应用构建脚本 (ZXing, AppCompat, WebKit)
    └── src/main/
        ├── AndroidManifest.xml     # 权限、Activity 声明
        ├── assets/
        │   └── app.html            # 单文件前端 (HTML+CSS+JS)
        ├── java/com/xuewu/bvds/
        │   ├── MainActivity.java   # WebView 宿主
        │   ├── BridgeInterface.java # JS 桥接 (API/下载/解析)
        │   ├── QRCodeGenerator.java # ZXing 二维码
        │   ├── DownloadManager.java # HTTP 下载工具
        │   └── MediaMerger.java    # 音视频合成 (MediaMuxer)
        └── res/
            ├── layout/activity_main.xml
            ├── drawable/           # 启动图标
            ├── mipmap-*/           # 启动图标 (多密度)
            └── values/             # 字符串、主题、颜色
```

## 架构

```
┌─ app.html (WebView) ──────────┐     ┌─ Java 层 ───────────────────┐
│ UI 渲染 + 用户交互            │     │                              │
│                               │     │ BridgeInterface              │
│ Native.apiGet()  ─────────────┼────→│   ├─ apiGet/apiPost (登录)   │
│ Native.fetchVideoInfo() ──────┼────→│   ├─ fetchVideoInfo (解析)   │
│ Native.downloadTask() ────────┼────→│   ├─ downloadTask (下载)     │
│                               │     │   └─ extractUrls (URL解析)   │
│ onVideoInfoResult() ←─────────┼─────│                              │
│ onTaskProgress()    ←─────────┼─────│ QRCodeGenerator              │
│ onTaskComplete()    ←─────────┼─────│ MediaMerger                 │
└───────────────────────────────┘     └──────────────────────────────┘
```

## API 接口

### 登录

| 端点 | 方法 | 说明 |
|------|------|------|
| `passport.bilibili.com/x/passport-login/web/qrcode/generate` | GET | 获取登录二维码 |
| `passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=...` | GET | 轮询扫码状态 |
| `api.bilibili.com/x/web-interface/nav` | GET | 验证登录态 |

### 视频解析

| 端点 | 方法 | 说明 |
|------|------|------|
| `www.bilibili.com/video/BVxxx` | GET | 获取视频页 HTML（提取 `__INITIAL_STATE__`） |
| `api.bilibili.com/x/player/playurl` | GET | 获取播放地址 (bvid + cid) |
| `api.bilibili.com/pgc/player/web/playurl` | GET | PGC/番剧 播放地址 (ep_id) |

### 参数

```
x/player/playurl?bvid=BVxxx&cid=xxx&qn=80&fnval=4048&fourk=1

  bvid     - BV号
  cid      - 视频分P ID
  qn       - 清晰度 (120=4K, 80=1080P, 64=720P, 32=480P, 16=360P)
  fnval    - 4048 = dash + dolby + 8K + av1
  fourk    - 1 = 允许 4K
```

## 功能

- 二维码扫码登录（Cookie 持久化）
- 短链接自动跳转 (b23.tv)
- 清晰度选择 (4K/1080P/720P/480P/360P)
- 三种下载模式：仅视频(.mp4) / 仅音频(.aac) / 视频+音频(.mp4)
- 并发下载（最多 3 个任务）
- 内置调试日志（长按标题栏 Logo）
- 主题切换（青色 / 琥珀）
- 下载路径可配置

## 构建

Android Studio 或命令行：

```bash
./gradlew assembleRelease
```

输出：`app/build/outputs/apk/release/app-release.apk`

## 声明

仅供学习交流，请勿用于违法用途。

## 许可

MIT
