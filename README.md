# WiFi 信号探测器

![自动构建](https://github.com/100apps/wifi-signal-detector/actions/workflows/release.yml/badge.svg)

独立 Android APK。无需 Termux。持续读取手机**当前已连接 Wi‑Fi** 的 RSSI，显示最近 60 秒的实时强度曲线，并在无地图底图的相对坐标平面上叠加信号采样点。采集页面在 APK 内；同一局域网设备也可以通过 App 显示的带随机密钥链接查看。

## 功能

- 每约 0.5 秒读取一次系统当前 RSSI，通过内置 WebSocket 实时推送。时序图横轴为时间，纵轴为原始 dBm，不做平滑或虚构小数精度。Android/无线芯片决定底层 RSSI 的实际刷新频率。
- 使用 GPS 与网络定位中较新的、估计误差较小的结果。默认以首个定位点为原点，显示后续东西、南北方向的相对位移；可把当前定位点设为新原点，或恢复首点原点。手动原点保存在本机浏览器存储中。
- 各精度等级的位置都可绘制。点的透明度表示定位质量，蓝色虚线圆表示最新定位的估计水平误差半径。图上距离刻度不是精度承诺；低精度轨迹只能作示意。
- 可选 GPS 高度剖面，可将时序图、位置图一起导出 PNG。采样历史存于 App 私有存储，停止采集不会删除历史，卸载 App 会删除历史。

## 安装与使用

从 [Releases](https://github.com/100apps/wifi-signal-detector/releases/latest) 下载 `wifi-signal-detector.apk`，安装后授权**精确位置**，保持 Wi‑Fi 和系统定位开启。App 内点击“启动采集”即可恢复停止的服务。画面上方持续显示已连接 Wi‑Fi 的强度曲线；位置图在获得首个定位后出现。

室内网络定位可能偏差数十米甚至更多。Android 报告的水平精度是 **68% 置信半径**，不是误差上限。当前手机若只有 GPS/网络定位，不保证房间内 1 米定位。相对位置图会始终显示来源和估计误差，请按其精度判断轨迹是否可信。高度也取决于手机是否提供具有足够垂直精度的 GPS 数据。

同一 Wi‑Fi 下其他设备可打开 App 显示的 `http://手机IP:8765/?key=...`。链接中的随机密钥用于限制访问，请只分享给可信设备。局域网连接是 HTTP，不适合跨不可信网络暴露。App 不向外部服务器上传位置或 RSSI。

## 构建

需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.1。Linux/macOS 设置 `ANDROID_HOME` 后运行 `bash build.sh`；Windows 运行 `pwsh -File build.ps1`。Windows 脚本也支持先前的 `PHONE_WIFI_SDK` 目录布局。输出为 `wifi-signal-detector.apk`。

本地 `build.sh` 在没有正式签名参数时会在被 Git 忽略的 `.signing/debug.jks` 生成开发签名。发布构建使用 `SIGNING_KEYSTORE` 与 `ANDROID_KEYSTORE_PASSWORD`。正式签名密钥不在仓库内；开发签名 APK 不能覆盖安装 Release APK。

## 自动发布

每次推送到 `master`，GitHub Actions 从源码构建、使用项目正式签名、验签，并创建一个带 APK 附件的 Release。也可在 Actions 页面手动触发。维护者需要配置仓库 Secrets：

- `ANDROID_KEYSTORE_BASE64`：正式 JKS 文件的 Base64 内容。
- `ANDROID_KEYSTORE_PASSWORD`：JKS 密码。

历史版本的正式签名已保留供本仓库 CI 使用，所以 Release APK 可以覆盖升级此前安装的版本。第三方 Fork 若用自己的签名，需要先卸载旧签名版本。

## 开源协议

[MIT](LICENSE)。
