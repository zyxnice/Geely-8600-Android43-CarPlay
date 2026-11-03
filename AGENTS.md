# 吉利 8600 维护仓库

- 本 checkout 的构建、安装说明见 README.md、docs/BUILD.md、docs/INSTALL.md；工作区授权与 OSS 分发规则见 D:/geely/AGENTS.md。
- 专用包 com.shihab.diplay.repair1.geely8600，API18/C3ALFUS/06.03.08600.H53.00060；构建使用 Gradle wrapper 和 -PgeelyPerformanceStage=B，认证资产必须显式输入。
- HUD 生命周期参照 shared/src/main/java/com/shilapi/xcertplay/hud/GeelyHudTrialPolicy.kt 与 GeelyHudTrialOutput.kt；保持同一 owner、代次失效及清屏顺序。
- 固定特权桥参照 compat/GeelyUsbOemBridge.java、GeelyVpnConsentHelper.java；新增 HUD 控制见 compat/GeelyHudExclusiveControl.java、GeelyHudExclusiveHelper.java、GeelyHudExclusivePolicy.java（均在 shared/src/main/java/com/shilapi/xcertplay 下）。不得扩展为任意包、命令或车辆透传。
- 设置 UI 参照 common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt 的 section/toggle/refreshGeelyHudStatus；启动恢复参照 BootReceiver.kt 与 GeelyHudRecoveryService.kt。
- HUD 独占决策、恢复边界和回退顺序见 docs/adr/ADR-0010-hud-exclusive-navigation.md。关闭／恢复后的旧 owner 不得再清理新导航的数据。
- 验证：:shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleGeelyUsb :mobile:assembleDebug；脚本 scripts/verify-geely-usb-apk.ps1 与 verify-geely-navigation-apk.ps1。原车恢复与 HUD 显示需实车单独验收。
- 2026-11-03 HUD 独占测试包已生成／分发，用户随后授权提交、推送并更新 GitHub 说明；不连接或安装到车机。沿用 audio-mix-rollback 9d2283914adc 的音频配置。
