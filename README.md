# ZBProxy Android

🚀 **ZBProxy 的 Android 版本** —— 一个简单、快速、高性能的多用途 TCP 中继，主要为搭建 Hypixel 加速 IP 而开发。

基于 [layou233/ZBProxy](https://github.com/layou233/ZBProxy) 的配置格式与功能设计，使用 Kotlin + Jetpack Compose 原生重写。

## ✨ 功能特性

- ☝ **一键启动/停止**代理服务
- 📋 **高可自定义的配置**（JSON，兼容原版 ZBProxy 格式）
- 🔌 **TCP 双向中继**，支持 Minecraft 协议
- 🏠 **Hypixel 主机名重写**，绕过登录地址检测
- 👮 **访问控制**（IP 白名单/黑名单）
- 💻 **实时日志查看**，支持级别过滤
- 🔮 **Material Design 3** 动态色彩主题
- 🤖 **前台服务**后台运行 + 通知栏控制
- 📱 **兼容 Android 8.0 - 16**（API 26 - 35）

## 📦 构建

### GitHub Actions（推荐）

推送到仓库后自动构建，在 **Actions** 页面下载 APK artifact。

### 本地构建

1. 使用 Android Studio（Ladybug 或更新版本）打开项目
2. 等待 Gradle 同步完成
3. 点击 **Build → Build APK(s)**

或命令行：

```bash
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```

## 🚀 使用方法

1. 安装 APK 并打开
2. 在 **Services** 页配置监听端口和目标地址（默认 Hypixel: `mc.hypixel.net:25565`）
3. 回到首页点击 **Start** 启动代理
4. 在 Minecraft 客户端输入你的代理服务器地址即可加入游戏

## 📂 项目结构

```
app/src/main/java/com/zbproxy/android/
├── App.kt                  # 应用入口
├── MainActivity.kt         # 主界面（MD3 + 底部导航）
├── proxy/
│   ├── ConfigModels.kt     # 配置数据模型（兼容 ZBProxy JSON）
│   ├── ConfigManager.kt    # 配置管理
│   ├── ProxyServer.kt      # 代理服务器核心
│   ├── TcpRelay.kt         # TCP 双向中继
│   └── MinecraftProtocol.kt # Minecraft 握手嗅探/重写
├── service/
│   └── ProxyForegroundService.kt  # 前台服务
├── ui/
│   ├── theme/              # MD3 主题（动态色彩）
│   ├── navigation/         # 底部导航
│   └── screens/            # 各页面
└── util/
    └── LogCollector.kt     # 日志收集器
```

## 📄 许可证

MIT License

## 🙏 致谢

- [layou233/ZBProxy](https://github.com/layou233/ZBProxy) - 原始项目
- Jetpack Compose / Material Design 3