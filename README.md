# Recorder

一个原生 Android/Kotlin 录音 App，支持：

- AAC/M4A 录音
- 暂停、继续、停止
- 前台服务录音
- 录音列表和播放
- 长按通过系统分享录音
- 将文件保存到公共 `Music/Recordings` 目录
- 锁屏后通过麦克风前台服务继续录音，不在锁屏显示录音提示

## 构建

```bash
./gradlew :app:assembleDebug
```

生成的 APK：`app/build/outputs/apk/debug/app-debug.apk`

首次录音需要授予麦克风权限。录音文件使用 `MediaStore` 写入公共音乐目录，不依赖 `READ_EXTERNAL_STORAGE` 或 `WRITE_EXTERNAL_STORAGE`。

录音使用 Android 前台服务运行，系统仍要求服务持有一个低重要性的服务通知。该通知设置为锁屏隐藏，应用不会额外请求通知权限；系统自带的麦克风隐私指示器由 Android 控制，应用无法关闭。
