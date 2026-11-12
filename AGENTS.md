# 吉利 8600 维护仓库

- 2026-11-12版本称呼：A版本固定指Host＋CDP流畅稳定USB基线，源码3c6cc29、APK c0628c96dede；来自milestone/usb-smooth-userspace-ncm-20261108并已收录main。用户确认其他功能正常，CarPlay通道打开及连接建立耗时过长尚未优化完成。原始c51d09c源码快照、4c21旧包及workspace52均须按各自身份区分，不能仅凭分支名或版本号混用。
- 本 checkout 的构建、安装说明见 README.md、docs/BUILD.md、docs/INSTALL.md；工作区授权与 OSS 分发规则见 D:/geely/AGENTS.md。
- 专用包 com.shihab.diplay.repair1.geely8600，API18/C3ALFUS/06.03.08600.H53.00060；构建使用 Gradle wrapper 和 -PgeelyPerformanceStage=B，认证资产必须显式输入。
- HUD 生命周期参照 shared/src/main/java/com/shilapi/xcertplay/hud/GeelyHudTrialPolicy.kt 与 GeelyHudTrialOutput.kt；保持同一 owner、代次失效及清屏顺序。
- 固定特权桥参照 compat/GeelyUsbOemBridge.java、GeelyVpnConsentHelper.java；HUD独占控制已于2026-11-04撤销，不新增原车应用启用状态操作。
- 2026-11-08 main已合入画面流畅里程碑与APP运行时Host＋CDP准备：用户态NCM（GEELY_KERNEL_NCM=false），GEELY_USB_RUNTIME_RECOVERY=true。实装c0628c96dede对应源码3c6cc29，用户确认验证后无明显大问题，见 docs/USB-HOST-CDP-ACCEPTANCE-20261108.md；不扩写首次成功率、冷启动和长期稳定性验收。准备入口与规则参照 compat/GeelyUsbRuntimeRecovery.java、GeelyUsbRuntimePolicy.java、GeelyUsbRuntimeHelper.java；已就绪不调用helper，空发现不再自动断电。
- 内核NCM备用代码的网卡事实归 transport/GeelyKernelNcmLink.kt，受限启用归 compat/GeelyKernelNcmPreparation.java、GeelyKernelNcmUpPolicy.java、GeelyKernelNcmUpHelper.java；复用现有固定桥/租约/API18编译方式，调用方参照 orchestration/CarPlayController.kt。该功能仅内核路径启用，当前用户态路径不启动内核网卡；不DOWN、不设IP/路由/DNS、不改默认/persist/ADB设置。见 docs/BUILD.md、ADR-0012与 docs/aegis/work/2026-11-08-smooth-usb-integration/20-checkpoint.md。
- 设置 UI 参照 common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt；启动参照 BootReceiver.kt。
- HUD现行行为见 docs/GEELY-8600-HUD.md；ADR-0010已撤销，仅保留历史。
- 讯飞仅按键维护候选参照 scripts/iflytek-button-only/README.md、docs/IFLYTEK-BUTTON-ONLY-20261104.md 与 ADR-0011；不参与DiPlay构建。原厂APK/ODEX、反编译树、生成产物和现场日志不入库；当前仅按键验收未通过。源码归档或推送不授权再次操作车机，测试用户唤醒词时禁止同时自动触发语音入口。
- 验证：:shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleGeelyUsb :mobile:assembleDebug；脚本 scripts/verify-geely-usb-apk.ps1 与 verify-geely-navigation-apk.ps1。HUD 显示与 USB 稳定性需实车单独验收。
- 2026-11-03 HUD 独占测试包已生成／分发，用户随后授权提交、推送并更新 GitHub 说明；不连接或安装到车机。沿用 audio-mix-rollback 9d2283914adc 的音频配置。

- 2026-11-04 用户要求撤销HUD独占模式，恢复5833883应用逻辑，保留HUD、音频、USB修复；同步提交推送说明，不连接或安装到车机。
