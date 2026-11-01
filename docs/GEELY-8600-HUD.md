# 8600 CarPlay 导航观测与 HUD 接入进度

## 当前交付（2026-11-01 修订）

HUD trial2 已覆盖安装到目标车机，用户本次现场反馈“可以了”；功能代码 `1fbc7cc` 已合入并推送 `main`。实现为**默认关闭、每次应用运行需手动确认的原厂 HUD 试验出口**。本次可用反馈不等于全部图标、原车接管、异常残留与长期稳定性都已验收。当前候选 SHA 和分项状态见[当前状态](GEELY-8600-STATUS.md)。

构建基线为已装车的 USB两秒r2 + VPN修复 + output3媒体源码。独立构建副本逐文件保留这些文件，不混入工作区output4诊断。包名/versionCode继续保持可同签覆盖，候选以文件名和SHA区分，不卸载清数据。原先安装后关机的要求已由用户取消；安装与调试记录不代表存在持续无人值守操作授权。

入口在设置页“吉利 HUD 导航试验”。开启前确认停车/台架、原车导航未在引导及原厂状态变化/残留风险；应用重启不恢复授权。页面每秒同步实际开关和拒绝原因。原车语音绑定和后台自动服务可以启动高德，因此本次修订不再以进程存在作为导航冲突证据。

数据链路：iAP2 0x5201/0x5202 → Iap2RouteState → GeelyNavigationFrame → GeelyHudTrialPolicy → **显式**原厂AutoNaviReceiver。Apple也明确HUD应使用iAP2导航元数据，而非截取主屏视频，见[WWDC23](https://developer.apple.com/videos/play/wwdc2023/10150/)及[WWDC25](https://developer.apple.com/videos/play/wwdc2025/216/)。本次实机已收到并解析目标手机的路线、转向、距离和路名；其他手机、导航应用或版本是否提供同样字段仍需分别验证。

- `GeelyHudTrialSettings/GeelyHudTrialLease`：进程内同意、单一owner，停用后保留互斥直到清理结束；旧关闭任务不能释放新owner。
- `GeelyHudTrialPolicy`：开始8、实时引导、结束9；独立距离更新时间超过5秒时尝试清除，稳定数据不重复发送。清除失败最多3次，错误在界面与日志保留。这里是应用侧截止时间，不代表物理HUD的自动TTL。
- `GeelyHudTrialOutput`：仅API18/C3ALFUS/H53.00060固定包启用；同时校验原厂receiver导出/可用/权限、versionCode6/versionName0.6800、APK与ODEX哈希。显式目标不向其他接收器泛发。不调用USB root桥、0x75a车辆透传或MCU/CAN。
- `GeelyFactoryNavigationState` 根据外源10019的8/10开始、9/12结束和10001的路线证据维护状态。默认空字段不证明导航开始或空闲；已知ACTIVE不因超时消失。未启用试验时记录状态，但不把控制器提前锁死。检测到导航接管后不发送全局结束消息覆盖新路线。
- 候选回调携带通道代次，交付和每次Binder调用前重新检查；正在执行的Binder不可撤回。没有把输出锁跨到CarPlay控制线程。

### 原厂契约与限制

固件 `AutoNaviReceiver.onReceive`、`AutoNaviRpc.setHud_NL/setNaviStatus` 确认：10019/EXTRA_STATE=8开始，9结束；结束路径清除方向、路名及下一路口距离等。10001使用ICON、SEG_REMAIN_DIS、NEXT_ROAD_NAME、ROUNG_ABOUT_NUM及可选剩余量。开始/结束会触及全局导航状态，不能宣称只影响HUD；不存在按来源原子占有的已核验接口。

原厂APK SHA256：`39c5da198ec14a1995772d8920dba7ad6859a4350bdb3c4b913db9d2a650322e`；ODEX SHA256：`ce1779e14124d3d55a8300d847b110a97910bceedcd2c517828d5420956ee154`。预优化APK单独缺DEX，不能只验APK就推定执行代码相同，因此两者都固定。身份不符则关闭试验，不自动降级特权或绕过守卫。

广播监控与实际发送不是原子操作，也无法发现只向原厂显式发送且不经过本应用的所有其他来源。初始UNKNOWN不等于确认空闲；允许同次人工确认原车未在导航后尝试，不能表述为自动互斥已通过。10001路线证据判据属于受控试验边界，尚未覆盖完整高德协议。强杀、断电、应用被系统杀死及结束发送失败后的物理恢复仍待验证；不自动补发未知来源的全局结束。

### 当前构建与制品

当前源码构建入口见[构建说明](BUILD.md)，本候选使用 `-PgeelyPerformanceStage=B`。装车 trial2 从实装 trial1 源码仅修改 HUD 状态相关九项文件，媒体、USB和VPN保留。817项回归中814通过、3项既有跳过；geelyUsb/debug构建、USB/签名/认证资产及metadata隔离检查通过。导航校验器用 `-Mode Trial`，普通构建用 `-Mode Disabled`。详细结果见[本次安装与反馈](aegis/work/2026-11-01-geely-hud-source-state/90-evidence.md)，[旧trial1记录](aegis/work/2026-10-31-geely-hud-output/90-evidence.md)保留为历史。

下一次停车试验需分别确认真实手机数据、HUD方向/距离/路名、结束和过期清除、原车导航恢复。实际输出保持手动关闭，未经本次人工确认不会发送导航。

## 历史观测版本边界（2026-10-26）

以下各节保留原会话的只观测交付记录和当时未完成项；“当前/本版本”在以下历史内容中指10月26日观测版，现状以上方11月1日章节及[当前状态](GEELY-8600-STATUS.md)为准。

本分支从 fork main `5ab0777` 开发，8600 `geelyUsb` 变体版本后缀为 `H53-USB1-NAVOBS1`。当前实现只接收并记录 iPhone 导航元数据，生成离线候选字段；没有原厂导航广播发送器、车辆透传调用或可打开的 HUD 输出开关。本版本不能将 CarPlay 导航显示到 HUD。

不沿用 P21/P22 的实车验收作为本版本证据。USB1 仍有独立的实车连接验收缺口，见 [USB1 说明](GEELY-8600-USB1.md)。普通 debug 构建没有导航观测 metadata，保持既有导航输出选择。

## 代码归属

- `Iap2RouteState`：共用 iAP2 增量解析，输出原始 Apple 转向/驾驶侧、米、秒和 Unix 秒；`BydHudRouteState` 保留既有 BYD 返回契约和图标映射。
- `GeelyNavigationFrame`：仅生成候选 `ICON`、`ROUNG_ABOUT_NUM`、下一路口距离、路名及可用的剩余量，不构造 Android Intent。映射尚未实车验证。
- `GeelyNavigationObserver`：控制通道代次、观测状态和节流诊断。距离独立计时，缺失/过期/超范围距离不成为候选；未明确驾驶侧、未知转向或不能表示为 GB2312 的路名不成为候选。
- `GeelyNavigationObservation`：有界异步队列，避免解析和日志阻塞 CSM。溢出/解析异常使本控制器观测关闭；新控制器才可重启观测。无线连接代次单调检查，旧排队任务不能取代新通道。
- `CarPlayController`：在三个现有 iAP2 帧入口接线，正常会话结束暂停、重连重置、关闭后拒绝迟到帧。8600 观测模式不启动 BYD HUD/仪表绑定。

## 启用条件与诊断

必须同时匹配 `SDK_INT=18`、`BOARD=C3ALFUS`、`DISPLAY` 以 `06.03.08600` 开头，且应用清单 metadata `com.shilapi.xcertplay.GEELY_NAVIGATION_OBSERVE=true`。只有现有 `geelyUsb` 变体声明该 metadata，不靠手机导航 App 名称或包名猜测能力。

诊断复用现有连接日志和“保存诊断报告”入口：

| 标记 | 能证明的内容 |
|---|---|
| `observe-only channel=... vehicleOutput=blocked` | 一个新的观测控制通道建立；不证明收到导航或 HUD 显示 |
| `routeFrames` / `maneuverFrames` | 分别收到 0x5201 / 0x5202 的数量；不证明字段语义有效 |
| `status=incomplete-or-expired` | 尚无完整有效路线，或本地解析数据已过期 |
| `status=missing-or-out-of-range-distance` | 当前动作没有新鲜、可表示为 int 的独立距离 |
| `status=missing-driving-side` | 手机未明确给出当前动作的驾驶侧 |
| `status=unsupported-maneuver-or-road` | 转向/驾驶侧或路名编码无法生成候选 |
| `status=candidate-only` | 当前数据能生成离线字段，不是车辆接收或显示成功 |
| `disabled-queue-or-decoder-failure` | 观测队列溢出或解析异常，已关闭；正常 CarPlay 控制继续 |

日志记录原始 Apple 值和候选字段，稳定状态每五秒汇总一次，状态变化立即记录。`noGuidanceFrames` 包含没有产生可用引导变化的帧，不能当作“非法包数量”。当前日志不是完整逐包抓取文件，不能声称已保存所有原始 TLV。30 秒新鲜度与 3 秒空列表隐藏继承现有解析行为，均不是已验证的车辆安全超时。

## 构建与离线验证

按 [构建说明](BUILD.md) 准备本地依赖。源码验证明确不提供认证输入：

```powershell
Remove-Item Env:DIPLAY_AUTH_ASSETS_DIR -ErrorAction SilentlyContinue
cmd.exe /d /c gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleDebug :mobile:assembleGeelyUsb --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'
./scripts/verify-geely-navigation-apk.ps1 -ApkPath mobile/build/outputs/apk/geelyUsb/mobile-geelyUsb.apk -SdkDirectory D:/AndroidSDK -Mode Observe -SourceOnly
./scripts/verify-geely-navigation-apk.ps1 -ApkPath mobile/build/outputs/apk/debug/mobile-debug.apk -SdkDirectory D:/AndroidSDK -Mode Disabled -SourceOnly
```

测试覆盖：缺字段与真实零米区别、动作切换不能借用旧距离、距离过期后保活不能恢复候选、未知驾驶侧/转向、中文编码、可选数值溢出、迟到帧、暂停/关闭、旧无线代次、慢日志和有界背压；现有 BYD/Glance 等回归继续运行。最终制品检查 metadata 隔离、API18、DEX035、导航类、v1 签名和源码包认证资产缺席。

上述 APK 是无认证资产的源码验证包，不能作为可独立连接 iPhone 的车测包。实际车测包仍需用户已授权的认证输入和独立构建/哈希核查。不得把源码构建成功当作真实 CarPlay 连接成功。

## 原厂候选契约与尚未解除的门槛

原厂 `AutoNaviReceiver` 接收 `AUTONAVI_STANDARD_BROADCAST_SEND`。10001 读取 `ICON`（不是 BYD 的 `NEW_ICON`）、`SEG_REMAIN_DIS`、`NEXT_ROAD_NAME`、`ROUTE_REMAIN_DIS`、`ROUTE_REMAIN_TIME` 等，并按车型调用 `setHud_NL`。候选字段并不是完整可发送协议：缺失值在接收器内可能是 -1（跳过/保留），清除路径使用的值与普通缺失值不能混用。

10019 的开始/结束会触发全局导航模式，并在条件满足时触发能量管理；10001 同样涉及摄像头、服务区、收费站、隧道等。接收器使用单例导航状态，没有查到按来源隔离的入口。因此当前不会发送这两种消息，不能通过省略 10019 或仅 setPackage 就宣称纯 HUD 输出。

后续顺序：

1. 经授权建立真实 CarPlay 会话，只读观察目标 iPhone/高德版本是否提供需要的数据；核对实际固件、包版本、接收组件和状态来源。
2. 完成原厂出口的权限、副作用、缺失值和清除契约核查。若广播不满足要求，继续定位更窄的原厂接口；不自动转为 MCU/CAN 或特权助手写入。
3. 确定可信的原车高德活跃状态和可执行的互斥策略。原车接管后，DiPlay 不得补发全局结束消息；仅手动互斥时应明确列为限制。
4. 经另行授权，在停驶/台架且已确认恢复手段时进行有限输出试验，验证实际图标、距离、路名、失活超时、强杀/崩溃残留及原车恢复。只读采集不能独自证明接收端 TTL。
5. 修改 ADR 和输出实现，重新独立审查及正常入口实车验收后，才考虑日常启用。

## 回退

当前只观测，不改变车辆显示。关闭 metadata 或回退原测试 APK 即撤销应用改动；不需要修改固件或 USB 角色。未来若启用车侧输出，必须先验证物理 HUD 恢复方法，不能认为卸载应用会清除车辆侧残留。
