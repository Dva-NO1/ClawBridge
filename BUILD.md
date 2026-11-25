# 构建与打包指南 (BUILD.md)

本文档说明如何在全新环境中分别构建 **中继服务 (`relay/`)**、**Windows 桌面桥 (`bridge/`)** 与 **Android 客户端 (`android/`)**。

---

## 1. 中继服务 (`relay/`)

### 环境要求
- Node.js ≥ 20（推荐 Node.js 20 LTS 或 22 LTS）
- Ubuntu 22.04+ / Debian 12+（用于生产 VPS 部署）

### 安装依赖与本地自测

```bash
cd relay
npm install --omit=dev
# 运行中继协议自测（监听本机 127.0.0.1，不依赖公网）
node src/selftest.mjs
```

### 手动启动（本地调试）

```bash
cp .env.example .env
# 按需修改 RELAY_HOST / RELAY_PORT / RELAY_STATE_FILE
RELAY_HOST=127.0.0.1 RELAY_PORT=8787 node src/server.mjs
```

### 生产 VPS 部署
详见根目录 [README.md](./README.md) 第 1 步，或直接运行：
```bash
sudo bash relay/deploy/deploy.sh --domain relay.yourdomain.com --email you@yourdomain.com
```

---

## 2. Windows 桌面桥 (`bridge/`)

### 环境要求
- Windows 10 / 11 (x64)
- Node.js ≥ 22（需内置 `node:sqlite`）
- .NET Framework 4.x（Windows 10/11 自带 `csc.exe`，用于编译零依赖 WPF 托盘宿主）

### 安装依赖

```powershell
cd bridge
npm install --omit=dev
```

### 编译零黑窗 WPF 托盘程序 (`ClawBridgeDesktop.exe`)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\bridge\scripts\build-desktop-gui.ps1
```
成功后会在 `bridge\ClawBridgeDesktop.exe` 生成原生托盘宿主程序（内置 `clawbridge.ico` 图标，后台拉起 `node.exe` 且无控制台黑窗）。

### 注册开机自启计划任务（一键安装）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\bridge\scripts\install-bridge.ps1
```

### 手动前台调试运行

```powershell
# 可选：用环境变量指定临时数据目录，避免影响正式配置
$env:CLAWBRIDGE_DATA_DIR = "$env:TEMP\clawbridge-dev"
node --no-warnings .\bridge\src\index.mjs
```

### 本机回环联调自测（中继 + 桌面桥 + 模拟手机）

```powershell
node .\bridge\src\selftest-loopback.mjs
```

---

## 3. Android 客户端 (`android/`)

### 环境要求
- **JDK 17**（必须为 Java 17，设置 `JAVA_HOME`）
- **Android SDK**（`compileSdk = 35`、`build-tools 35.0.0`，设置 `ANDROID_HOME`）

### 3.1 构建 Debug APK（无需签名密钥，最快体验）

```bash
cd android
./gradlew assembleDebug --no-daemon
# 输出路径：android/app/build/outputs/apk/debug/app-debug.apk
```

Windows PowerShell：
```powershell
cd android
.\gradlew.bat assembleDebug --no-daemon
```

### 3.2 生成自签名密钥并构建 Release APK

1. 使用 JDK 自带的 `keytool` 生成你自己的签名密钥库（**切勿提交到 Git**）：
   ```bash
   mkdir -p android/keystore
   keytool -genkeypair -v \
     -keystore android/keystore/release.jks \
     -alias clawbridge \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. 复制示例配置文件并填入你的密码：
   ```bash
   cp android/keystore.properties.example android/keystore.properties
   ```
   编辑 `android/keystore.properties`（已被 `.gitignore` 忽略）：
   ```properties
   AGM_RELEASE_STORE_FILE=keystore/release.jks
   AGM_RELEASE_STORE_PASSWORD=your_store_password
   AGM_RELEASE_KEY_ALIAS=clawbridge
   AGM_RELEASE_KEY_PASSWORD=your_key_password
   ```
   > 也可以不写文件，直接通过环境变量或 Gradle `-P` 参数传入上述 4 个变量。
3. 执行 Release 混淆压缩构建：
   ```bash
   cd android
   ./gradlew assembleRelease --no-daemon
   # 输出路径：android/app/build/outputs/apk/release/app-release.apk
   ```
4. 校验 APK V2 签名：
   ```bash
   $ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
   ```

### 3.3 运行 Android 单元测试与 UI 布局审计

```bash
cd android
./gradlew testDebugUnitTest --no-daemon
```
> 注：如在 Windows 下路径包含中文导致 AAPT2 报错，可将 `android/` 映射到纯 ASCII 路径（或将仓库克隆至纯英文目录如 `C:\dev\ClawBridge`）后再执行 Gradle 构建。
