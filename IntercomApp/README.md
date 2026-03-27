# IntercomApp - 對講機 Android App

## 功能
- SIP 登錄 Asterisk（分機 101-108）
- 有人按門鈴時自動響鈴（即使在背景）
- 顯示門口 MJPEG 即時影像
- 雙向語音通話（SIP RTP）
- 一鍵開門（HTTP API）

## 建置需求
- Android Studio Hedgehog 或以上
- Android SDK 34
- JDK 17

## 步驟

### 1. 開啟專案
```
File → Open → 選擇 IntercomApp 資料夾
```

### 2. 等待 Gradle 同步
Linphone SDK 約 100MB，首次下載需要時間。

### 3. 建置 APK
```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```
APK 在 `app/build/outputs/apk/debug/app-debug.apk`

### 4. 安裝到手機
- 手機開啟「安裝未知來源 App」
- 用 USB 傳輸或 ADB 安裝

### 5. 設定
- 開啟 App → 設定
- 伺服器 IP：192.168.100.163
- 分機：101（第一支手機），102（第二支），以此類推
- 密碼自動填入

## Pi 端設定確認
- Flask 已新增：
  - `GET /camera/stream` - MJPEG 串流
  - `GET /camera/snapshot` - 快照
  - `POST /api/door/unlock/token` - Token 開門

## 架構說明
```
Pi（192.168.100.163）
├── Asterisk:5060  - SIP 伺服器
├── Flask:5000     - Web API + 攝像頭串流
└── AMI:5038       - Asterisk Manager

Android App
├── SipService     - 後台 SIP（Foreground Service）
├── IncomingCallActivity - 來電畫面
├── CallActivity   - 通話中
├── MjpegView      - MJPEG 串流顯示
└── ApiClient      - HTTP 開門 API
```
