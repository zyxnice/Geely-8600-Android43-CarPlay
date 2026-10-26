# 8600 HUD 应用侧接入

- 目标：按本次独立审查后的六阶段计划，在独立分支开发可构建、可观测、可离线验证的吉利导航适配；实车输出以原厂契约、互斥与异常清除证据为前提。
- 分支：`codex/geely8600-hud-integration`。基线：fork main `5ab0777fa4a99df619e36e5fb25d0d7d6628dfb5`，开始时工作区干净、与 origin/main 一致，无其他 worktree 或进行中的 Git 操作。
- 已读基线：README、docs/BUILD.md、docs/GEELY-8600-USB1.md、ADR-0001、P21 说明；CarPlayController、BydNavigationOutputs、BydHudRouteState、BydClusterFrame、导航订阅及对应测试；本地 8600 AutoNaviReceiver/AutoNaviRpc 反编译。
- 用户授权：开发并创建独立 Git 分支；未授权实车安装/广播、刷机、外部发布。阶段性检查不要求重复审批，独立代码和测试持续执行。
- 范围：共用 iAP2 解析、会话代次与完整性、吉利候选字段映射、8600 变体观测接入、有限诊断、协议回放/构建/制品回读、独立 diff 复核。
- 非目标：P22 整合、USB 新方案、MCU/CAN 写入、原厂应用替换、修改固件、自动打开车侧输出。
- Change Necessity：现有帧出口面向 BYD 且字段不兼容，需增加吉利观测 owner；共享解析只作局部提取，BYD 既有契约保持。
- 架构边界：原厂广播是候选接口，不能因 exported=true 或 sendBroadcast 成功视为 HUD 验收。10019 涉及能量管理；10001 也涉及摄像头/隧道等，当前不发送任何原厂导航消息。
- 复杂度：CarPlayController 仅接线；解析、候选字段、观测生命周期分别归属局部文件，不建立通用车辆插件框架。
- TDD Route：Mode off / Decision skipped；没有严格 TDD 授权。使用有针对性的协议/生命周期回归测试，覆盖真实风险而非镜像实现。
- 验收：相关旧测试不回归；新测试覆盖缺字段、增量、重连/迟到帧、未知转向、过期和观测结束；API18 可构建；最终 APK 中 metadata/DEX 与源码对应；独立复核无未处理实质问题。
- 完整目标的外部依赖：目标 iPhone 高德元数据、车辆连接、实际原厂接口权限/副作用、接收端 TTL 与导航互斥仍需实车证据。未满足时明确 needs-verification，保持输出关闭。
