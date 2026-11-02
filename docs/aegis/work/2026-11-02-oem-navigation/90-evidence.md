# 交付证据

2026-11-02：用户要求的新分支候选已构建并上传 OSS。

- 分支：`codex/oem-navigation-integration-20261102`。
- 基线：`1909c95`，保留 oem-sequence HUD 的 10019 开始/结束和 10001 引导出口。
- 音频：首 PCM 请求 stream11 导航焦点；硬件排空后静默500ms释放；拒绝和被抢占时该段静音，不恢复旧方向；新增原车导航音量入口。
- 构建：shared/common 单元测试及 geelyUsb/debug APK 成功；823项，820通过、3跳过、0失败、0错误。
- 校验：USB3-B、相同包名/签名、API18、ARMv7、v1签名、DEX035助手、显式认证资产；导航专用包 Trial 和普通包 Disabled 均通过。
- 独立复核：见30-review.md；git diff --check通过。
- OSS：上传HTTP200，匿名完整下载HTTP200，大小18,707,776字节，SHA-256一致。
- SHA-256：`02057f56017987f6b064572040d12ff364d5c3fa2bd044409a52dfb796bf6cb8`。
- 下载：https://meice-test.oss-cn-shanghai.aliyuncs.com/geely/apk/DiPlay-8600-OEM-navigation-20261102-02057f560179.apk
- 本地日志与收据：`D:/geely/output/oem-navigation-20261102`，build.log、verification-0/1/2.log、build-receipt.json、oss-upload-receipt.json。

未安装车机。HUD默认关闭，停车手动开启且仅保留一个导航来源；已有关闭原车音频的用户选择保留，需要在设置中选择原车配置后重连。500ms为CarPlay适配启发式，持续静音PCM不会释放焦点。实车HUD画面、导航音量、音乐压低恢复、来电抢占及真实UI操作尚未验收。
