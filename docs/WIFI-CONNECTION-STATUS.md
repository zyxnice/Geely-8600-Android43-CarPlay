# Wi-Fi 音乐：隔离原厂蓝牙媒体连接

更新日期：2026-11-13。

## 当前状态：1.0 正式版

用户已确认 USB 与 Wi-Fi 两种连接方式的问题全部解决，当前版本命名为 **1.0 正式版**。
下一阶段重点转为两种连接模式的流畅度优化，详见 [正式版身份与后续重点](RELEASE-1.0-20261113.md)。
1.0 沿用下方应用代码与 APK 字节，正式下载入口为 [v1.0.0](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/releases/tag/v1.0.0)。
以下保留该确认之前的排查、精简测试包和验证边界；旧“待实车回归”描述是历史阶段状态。

## 问题与实车证据

Wi-Fi CarPlay 音乐出现短暂出声后无声，部分场景进度仍推进。此前怀疑 AAC，
但无声窗口中存在持续非零 PCM 和 AudioTrack 播放头推进；它们不能单独证明扬声器出声。
同机 A/B/A 对照得到：原厂 A2DP 保持连接时无声；仅断开所选手机 A2DP 后有声；
恢复该 A2DP 后再次无声。HFP 免提连接全程保留，未操作音频焦点。

加入自动隔离的 BTMEDIA6 LOG、NOLOG 两个试验包均由用户确认音乐持续正常、暂停重播正常。
正常退出的恢复请求与原厂 A2DP 连接恢复也已核验。
这些试验包同时包含其他排查改动，**不等于本次精简提交已完成实车回归**。

## 本次保留的修复

- 仅在无线连接路径、API18、C3ALFUS、完整固件 `06.03.08600.H53.00060` 生效。
- 使用无线流程实际选定的手机地址，在 AirPlay 会话激活后临时断开其原厂 A2DP。
- 专用工作线程每秒检查自动重连；正常会话结束或无线栈退出时恢复本次释放的连接。
- 旧会话结束不干扰新会话；用户切换到另一部手机时不抢回连接；未主动断开则不主动恢复。
- 仅使用 `NeuC3AlfusBluetooth` 的媒体连接方法，核对 Binder 描述符；不改蓝牙总开关、
  HFP、配对、连接优先级和音频焦点。失败沿用关键节点诊断，不增加逐包日志。

实现参照 `GeelyWifiMediaGuard.kt`、`WirelessMediaLease.kt`，生命周期接入
`CarPlayController.startWireless()`、无线 session listener 与 `closeWirelessStack()`。
原 USB `H52WiredAudioGuard` 保持不变。

## 本次撤回与验证边界

按用户要求，撤回本次 Wi-Fi 工作树中其他未提交改动，包括 AAC 解码调整、音频缓冲、
额外诊断/NOLOG 构建、自动热点及默认无线启动。只保留蓝牙隔离及其测试、说明。
无线入口和热点配置恢复主线原有行为；蓝牙修复不再依赖试验热点开关。
流畅度优化不属于本次范围。旧试验 APK 不代表这份精简源码，不作为本提交发布产物。

单测覆盖释放/恢复、断开拒绝、读取失败、自动重连、会话替换、其他手机、最终恢复重试，
以及固件边界。构建检查与实车验收分开记录；精简后的组合仍需单独装车回归。
进程被系统强杀可能跳过退出回调，此时可从原车蓝牙页面恢复媒体连接。

本次精简源码已完成 `:shared:testDebugUnitTest :common:testDebugUnitTest`：959 项通过、
3 项既有跳过，无失败；11 项蓝牙隔离与固件边界用例通过。`:mobile:lintDebug` 无错误
（20 条警告），`:mobile:assembleGeelyUsb :mobile:assembleDebug -PgeelyPerformanceStage=B`
构建通过。随后按用户要求合入主线并发布以下测试 APK，未安装到车机；
没有以历史试验包验收替代本次精简版本实车验证。

## GitHub 测试版与安装条件

[发布页](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/releases/tag/v2026.11.13-wifi-bluetooth-only)
· [下载 APK](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/releases/download/v2026.11.13-wifi-bluetooth-only/DiPlay-8600-H53-WiFi-BluetoothOnly-da26e77-20261113-fe2daa9ada75.apk)
· [SHA256SUMS](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/releases/download/v2026.11.13-wifi-bluetooth-only/SHA256SUMS.txt)

| 项目 | 身份 |
| --- | --- |
| 应用源码与标签目标 | `da26e7788992044d4345ddd23206affd0f44ca47`，已合入 main |
| 标签 | `v2026.11.13-wifi-bluetooth-only`，Pre-release |
| APK 大小 | 5,591,858 字节 |
| SHA-256 | `fe2daa9ada750f687c24894c1a323673ad0b375f6b692ecddee5772c41e06abb` |
| 包名 | `com.shihab.diplay.repair1.geely8600` |
| 版本字段 | `0.2.10-H53-USB3-B` / versionCode 29，沿用主线变体命名 |
| 签名 SHA-256 | `f2ea4c3ab5186fc4aa783c8f09c5a52a32f9fda8c4537283ca8d495e3f6c6d79` |

附件完成 v1 签名、API18、ARMv7、单 DEX 与内容检查。包内包含蓝牙隔离实现，
不包含已撤回的 AAC 专项诊断、缓冲试验或自动热点入口，也不携带认证资产。
GitHub APK 与校验文件均已匿名完整下载读回，大小、内容和 SHA-256 与本地一致。
仅适用于已有认证身份的覆盖安装，保留应用数据，不卸载或清除数据。
测试前在原车设置手动开启热点，在 DiPlay 原有入口选择无线、车机热点及已配对手机，
核对热点名称和密码。本次不自动修改这些设置，不替换 USB A 稳定版。

## 与 USB A 版本的关系

USB 稳定 A 版为用户态 NCM＋Host/CDP 自动准备，APK 标识 `c0628c96dede`、应用源码 `3c6cc29`。
用户确认其他功能正常，连接建立耗时仍是独立事项。本次不修改 USB 和音视频公共实现。

[USB A 版本](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/releases/tag/v2026.11.12-usb-stable-a)
· [USB 版本记录](USB-HOST-CDP-ACCEPTANCE-20261108.md)
