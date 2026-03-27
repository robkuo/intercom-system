# 門口對講機系統

Raspberry Pi 4 為基礎的智慧對講機系統，整合臉部辨識、語音辨識、SIP 通話。

## 系統架構

```
訪客按鈕 → 臉部辨識 / 語音辨識 (Gemini) → SIP 撥號 → Android APP
     ↓                                              ↑
  Pi 攝影機                                   公司人員手機
```

## 主要元件

- **intercom_system/** - 主程式（Python）
  - `main.py` - 主控制程式
  - `gui/` - Tkinter 介面（主畫面、通話視窗）
  - `sip/` - SIP 客戶端（Asterisk AMI）
  - `face/` - 臉部辨識（InsightFace）
  - `door/` - 門鎖控制（GPIO Relay）
  - `utils/` - 工具函數

- **IntercomApp/** - Android SIP 應用程式（Kotlin）
  - 自製 SIP UDP 協定（MiniSipStack.kt）
  - RTP 音訊（RtpAudioSession.kt）

- **audio_bridge.py** - AudioSocket 音訊橋接（Asterisk ↔ ALSA）

- **voice_gate.py** - 語音識別閘門（GPIO 按鈕 → Gemini → 撥號）

## 硬體需求

- Raspberry Pi 4
- USB 麥克風（UACDemoV10）
- USB 喇叭（CD002AUDIO）
- USB 攝影機
- Relay 模組（GPIO 18）

## 軟體需求

- Asterisk PBX（PJSIP）
- Python 3.9+
- Android 8.0+

## 設定

1. 在 Pi 上安裝 Asterisk 並設定 PJSIP endpoints (100-108)
2. 複製程式到 Pi `/home/rob/intercom_system/`
3. 設定 `audio_config.json`
4. 安裝 Android APK 並設定 SIP 帳號

## 服務

```bash
sudo systemctl start intercom-gui
sudo systemctl start audio-bridge
sudo systemctl start voice-gate
```
