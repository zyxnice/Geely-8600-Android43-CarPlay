# 吉利 8600 项目约定

- 临时 OSS 测试包分发使用 `meice-test`（上海）下的 `geely/apk/`；用户已于 2026-11-02 确认。下载地址格式、授权范围、凭据来源与核验要求见工作区 `../AGENTS.md` 的“临时 OSS 测试包分发”，后续会话分发测试包前先读取该节。
- 对象：星越2019款8600原厂车机；当前专用变体为 `mobile:geelyUsb`，包名 `com.shihab.diplay.repair1.geely8600`。通用模拟器不能代替H53实车验收。
- 项目介绍、构建与安装入口：`README.md`、`docs/BUILD.md`、`docs/INSTALL.md`。打包使用Gradle wrapper，B媒体基线显式 `-PgeelyPerformanceStage=B`；认证输入仅用本地显式 `DIPLAY_AUTH_ASSETS_DIR`，不提交身份材料。
- 授权入口参照：`common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` 的 `requestVpnConsent` / `vpnConsent`。实时授权归系统 `VpnService.prepare`；本地信任标记不能代替系统权限。
- 平台兼容层参照：`shared/src/main/java/com/shilapi/xcertplay/compat/GeelyUsbOemBridge.java`（固定助手命令、参数和路径校验）、`GeelyUsbRuntimeRecovery.java`（平台guard、后台操作和单飞）、`GeelyUsbRuntimeHelper.java`（API18独立DEX与截止时间）。沿用这些类的Java组织、日志及失败语义，不新增通用任意shell入口。
- 策略测试参照：`shared/src/test/java/com/shilapi/xcertplay/compat/GeelyUsbRuntimePolicyTest.java`；VPN边界补充为 `GeelyVpnConsentPolicyTest.java`、`GeelyVpnConsentLeaseTest.java`。
- HUD参照：`hud/Iap2RouteState.kt`负责共用解析，`GeelyNavigationObservation.kt`负责异步代次与诊断，`GeelyHudTrialPolicy.kt`负责试验生命周期；这些路径均位于`shared/src/main/java/com/shilapi/xcertplay/`。UI沿用`DiPlayActivity.kt`的section/toggle/refreshStatus和中英文资源，不另造设置框架。
- HUD原厂出口限制见`docs/GEELY-8600-HUD.md`与ADR-0002。`GeelyFactoryNavigationState.kt`及其同名Test维护原车导航证据，不能以高德后台进程替代路线状态。仅手动停车试验，默认关闭；原厂APK/ODEX均核对。安装授权不等于后台发送HUD消息的授权，不能宣称广播监控是原子来源互斥或已验证强杀清除。
- VPN恢复决策与准确限制：`docs/adr/ADR-0007-geely-vpn-consent-recovery.md`。首次系统确认保留；恢复仅在固定H53平台的下一次内核boot尝试；不与系统内建VPN同时启动。不能宣称已发出的Binder操作可由本地超时撤回。
- 验证：`:shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleGeelyUsb`；包体核验用 `scripts/verify-geely-usb-apk.ps1`，保留API18 v1签名、ARMv7、DEX035助手、认证资产和同签替换身份。
- 车机的包名与版本号会在候选间复用，装车前还须核对APK SHA和准确基线；同签覆盖不卸载、不清数据。任务中的物理重启、原厂桥和CarPlay验收必须单独记录，不能由构建或模拟器结果代替。
