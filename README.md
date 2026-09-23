# recorder

一个原生 Android/Kotlin 录音 App，支持：

- AAC/M4A 录音
- 暂停、继续、停止
- 前台服务录音
- 录音列表、录音时间、播放进度、标记点和删除
- 后台播放，退出界面或锁屏后仍可继续播放
- 长按通过系统分享录音
- 将文件保存到公共 `Music/Recordings` 目录
- 录音数据实时写入，异常退出后保留已生成的文件并在下次启动时恢复
- 锁屏后通过麦克风前台服务继续录音，不在锁屏显示录音提示
- 自动检查 GitHub Release，并支持下载和安装新版本

录音列表会显示录音时长和创建时间。点击播放后可拖动进度、暂停/继续播放，并在当前播放位置添加带自定义名称的标记点；点击标记点即可跳转。长按录音可播放、重命名、分享或删除；重命名会自动保留 `.m4a` 扩展名，删除操作需要二次确认。

应用启动时会检查 GitHub Release，也可以点击“检查更新”。发现新版本后，应用会下载 APK 并打开 Android 系统安装确认页；Android 不允许普通应用静默安装更新。

## 构建

```bash
./gradlew :app:assembleDebug
```

生成的 APK：`app/build/outputs/apk/debug/app-debug.apk`

GitHub Release 使用固定的 Android 签名密钥构建，且每次发布会递增 `versionCode`，因此后续版本可以直接覆盖安装。仓库 Actions 需要配置以下 Secrets：

- `RECORDER_KEYSTORE_BASE64`
- `RECORDER_KEYSTORE_PASSWORD`
- `RECORDER_KEY_ALIAS`
- `RECORDER_KEY_PASSWORD`

如果手机上安装的是旧临时签名版本，首次切换到固定签名版本前需要卸载旧版本；从固定签名版本开始，后续 APK 可以直接覆盖更新。

首次录音需要授予麦克风权限。录音文件使用 `MediaStore` 写入公共音乐目录，不依赖 `READ_EXTERNAL_STORAGE` 或 `WRITE_EXTERNAL_STORAGE`。

录音使用 Android 前台服务运行，系统仍要求服务持有一个低重要性的服务通知。该通知设置为锁屏隐藏，应用不会额外请求通知权限；系统自带的麦克风隐私指示器由 Android 控制，应用无法关闭。
