# 吉利 8600 维护仓库

- 本 checkout 的构建、安装说明见 README.md、docs/BUILD.md、docs/INSTALL.md；工作区授权与 OSS 分发规则见 D:/geely/AGENTS.md。
- 专用包 com.shihab.diplay.repair1.geely8600，API18/C3ALFUS/06.03.08600.H53.00060；构建使用 Gradle wrapper 和 -PgeelyPerformanceStage=B，认证资产必须显式输入。
- HUD 生命周期参照 shared/src/main/java/com/shilapi/xcertplay/hud/GeelyHudTrialPolicy.kt 与 GeelyHudTrialOutput.kt；保持同一 owner、代次失效及清屏顺序。
- 固定特权桥参照 compat/GeelyUsbOemBridge.java、GeelyVpnConsentHelper.java；HUD独占控制已于2026-11-04撤销，不新增原车应用启用状态操作。
- 设置 UI 参照 common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt；启动参照 BootReceiver.kt。
- HUD现行行为见 docs/GEELY-8600-HUD.md；ADR-0010已撤销，仅保留历史。
- 验证：:shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleGeelyUsb :mobile:assembleDebug；脚本 scripts/verify-geely-usb-apk.ps1 与 verify-geely-navigation-apk.ps1。HUD 显示与 USB 稳定性需实车单独验收。
- 2026-11-03 HUD 独占测试包已生成／分发，用户随后授权提交、推送并更新 GitHub 说明；不连接或安装到车机。沿用 audio-mix-rollback 9d2283914adc 的音频配置。

- 2026-11-04 用户要求撤销HUD独占模式，恢复5833883应用逻辑，保留HUD、音频、USB修复；同步提交推送说明，不连接或安装到车机。
