# 8600 VPN 开机授权恢复

日期：2026-10-30。用户授权修改代码、本地验证、打包，并补充要求 output1/output3 两个修复分支；不装车、不发布或推送。实车冷启动验收另行执行。

目标：系统首次授权后，H53 冷启动恢复本应用 VPN 授权，减少每天重复确认。首次未授权、当次拒绝、同次系统启动内撤销、其他 VPN 占用、平台不符或恢复失败时保留系统授权流程。

边界：仅 API18/C3ALFUS/06.03.08600.H53.00060 和 geelyUsb 包，复用原厂 helper 启动桥；不改系统文件、不改 USB/媒体。不同应用 VPN owner 用CAS保护，已有内建VPN拒绝；并发启动内建VPN存在固件接口竞态。授权恢复依据系统 prepare 复核，不能仅凭命令文本成功启动。超时/取消不能撤回已发出Binder；实际已授权则继续，未授权显示系统提示，不能承诺任何失败一定显示弹窗。详见ADR-0007。

TaskStartSnapshot：HEAD aaec231；原有未提交改动为视频接收/输出诊断及其测试、FrameCadenceStats 和既有未跟踪文档/脚本，全部保留。此次文件限定 VPN 恢复类/测试、HostActivity 入口、helper 构建注册、geelyUsb opt-in 及决策/验证文档。打包基线待用户选择 output3/output1，独立副本构建，避免混入 output4。

参照：GeelyUsbOemBridge.java（固定命令与路径校验）；GeelyUsbRuntimeRecovery.java（平台限制、异步 bounded single-flight）；GeelyUsbRuntimeHelper.java（独立 DEX/API18/watchdog）；CarPlayHostActivity.kt requestVpnConsent 和 vpnConsent（系统授权所有者）；GeelyUsbRuntimePolicyTest.java（纯策略测试）；scripts/verify-geely-usb-apk.ps1（API18/签名/资产核验）。

Change Necessity: code-change。固件 Vpn.mPackage 只保存在内存，普通配置不能跨重启保留；用已有 OEM 桥启动单次助手恢复授权，属于兼容层内受限扩展。高风险：系统授权边界，需独立 agent 审查实际 diff。TDD Mode: off，仍添加实际安全边界和异步恢复测试。

PatchShape：平台兼容恢复；CanonicalOwner：系统 Vpn 保存实时授权，应用兼容层只负责已确认信任后的跨启动恢复；不把本地标记冒充系统权限。最高风险反例：同次启动撤销后被自动授权，或抢占其他 VPN；以 boot_id 与 compare-and-set oldPackage 防止。ArchitectureReviewRequired: yes，记录 ADR Proposed，真机未验。

步骤：核验固件 API 与进程身份切换 → 固定助手/授权策略/异步入口 → 相关测试与 API18 进程验证 → 独立审查 → 指定基线干净打包/签名资产检查 → 报告实车未覆盖项。
