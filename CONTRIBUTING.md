# 贡献指南

欢迎提交问题和改进。请保持界面、文档与讨论使用中文。

## 开发

1. 安装 JDK 17、Android SDK Platform 35、Build Tools 35.0.1。
2. Linux/macOS 运行 `bash build.sh`；Windows 运行 `pwsh -File build.ps1`。
3. 修改 `assets/app.js` 后运行 `node --check assets/app.js`，并在手机或浏览器中检查实时曲线、定位降级、原点切换与 PNG 导出。
4. 在 PR 中说明改动、验证方式、定位与信号的实际采样依据。不要把模拟数据描述为实测结果。

## 发布约定

- `master` 的每次推送会自动创建 Release；PR 使用独立 CI 验证。
- 正式签名材料只放在 GitHub Secrets；不得提交 JKS、密码、访问链接中的随机密钥、精确位置记录或手机日志中的个人数据。
- Android 包名 `zh.dubbench.wifimap` 暂时保留，用于与早期 APK 兼容并支持覆盖升级。改包名会成为不同应用，必须单独讨论迁移方案。
- 修改图标时同步更新 `artwork/icon.svg` 与 `res/drawable-nodpi/ic_launcher.png`。可用支持 SVG 的绘图工具导出 512×512 PNG。

## 问题报告

请提供 Android 版本、手机型号、App 版本、Wi‑Fi 连接状态、复现步骤和预期结果。发布截图前请遮盖局域网访问密钥、SSID 和可能暴露位置的信息。
