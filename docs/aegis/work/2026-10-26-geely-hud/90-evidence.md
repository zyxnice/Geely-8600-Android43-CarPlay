# 验证证据

基线：`5ab0777fa4a99df619e36e5fb25d0d7d6628dfb5`。分支：`codex/geely8600-hud-integration`。

## 已取得的最终业务代码证据

- 执行 `:shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleDebug :mobile:assembleGeelyUsb`，退出码 0，最后一轮 `BUILD SUCCESSFUL in 2m 9s`。这是修复观测失效后媒体消息继续处理等最终业务改动后的验证。
- 读回 JUnit XML：shared 101 suites / 490 tests / failures 0 / errors 0；common 27 suites / 161 tests / failures 0 / errors 0。
- `verify-geely-navigation-apk.ps1` 对观察变体 `-Mode Observe -SourceOnly` 和普通 debug `-Mode Disabled -SourceOnly` 均退出码 0。分别确认 metadata=true 与 metadata 缺席；API18/ARMv7、v1 签名、应用 DEX035 和最终四个导航类存在；无 offline-mfi 认证资产。
- 观察 APK：`mobile/build/outputs/apk/geelyUsb/mobile-geelyUsb.apk`，SHA256 `4061A4869BF7E1D78A039ABC4143960DC7C442AA55F5004D62C59A494D73C1A8`。
- 普通 APK：`mobile/build/outputs/apk/debug/mobile-debug.apk`，SHA256 `5A3646063CA5445F20A405B662935F5442688DDB7408978B554D756102B0937F`。
- 独立 agent 实际 diff 审查及修订后复核：最终无新的必改项，结论仅支持观测版本。审查发现的四项边界问题均已修复并有对应测试。
- `git diff --check` 通过。定向检查新吉利代码未发现广播发送、车辆透传或 USB 特权桥调用；10019 只出现在禁止生命周期发送的说明注释内。
- lint：`:mobile:lintDebug` 与 `:mobile:lintGeelyUsb` 各 0 errors（分别 20 / 4 warnings）；`:shared:lintDebug` 13 errors / 60 warnings，`:common:lintDebug` 125 errors / 143 warnings，合并命令退出码 1。完整 lint 不通过，不作为发布就绪证据。SARIF 中新增导航类、BydHudRouteState 和 BydNavigationOutputs 无诊断命中；Controller 的报错在未修改的蓝牙权限代码段。逐项核对：137 项错误的代码上下文存在于基线提交，另 1 项为开始时已经存在、未跟踪的 local.properties 路径转义提示。未扩大任务修复既有权限/常量问题或改写本地配置，也未通过新增 lint baseline 隐藏错误。

## 未覆盖范围与恢复条件

- 实车正常入口接口自测未完成。未连接或操作车辆，未验证实际 iPhone 高德元数据、真实 CarPlay 连接、物理 HUD、来源互斥、车辆失活 TTL 和强杀/崩溃残留。
- 两个 APK 均为无认证资产的源码验证包，不能作为独立连接 iPhone 的车测交付；未发布、未推送远端。
- 构建、主机测试和 APK 格式/签名检查不证明 API18 实际运行或车辆显示。观测阶段证据强度 B；完整 HUD 目标为 C / needs-verification。
- 后续恢复按 docs/GEELY-8600-HUD.md 的五项现场与接口门槛；先取得授权及已核验恢复手段，再进行有限输出验证，不自动发送候选广播。

## 架构与复杂度核对

- 共用协议 owner 为 Iap2RouteState，BYD wrapper 保留既有返回与图标语义；吉利观测和候选映射不承担车辆协议发送。
- Controller 仅增加选择与通道接线；有界异步队列承接可选观测，失效后媒体/普通 CSM 处理继续。没有引入通用车辆框架或新外部依赖。
- ADR-0002 保持 Proposed；能力/限制已同步到 HUD 文档、README 和 USB1 说明，未把未执行的车侧方案记录为 Accepted。
