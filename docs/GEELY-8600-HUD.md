# 8600 CarPlay 导航观测与 HUD 接入进度

## 当前交付（2026-11-03 HUD 独占候选，未装车）

原车高德空数据与 CarPlay 数据竞争已由行驶隔离对照确认。用户要求由 DiPlay 控制，新增默认开启的「HUD 独占模式」：有效路线期间临时禁用原车高德，确认隔离后通过既有原厂接收端输出，结束后恢复原启用值。高德不存在或原本禁用时保持原状。保留导航语音通道11和已有路线失效规则，不再使用历史的独立距离5秒清除。

独占控制使用固定 H53 特权助手管理应用启用状态；导航数据仍只走显式 AutoNaviReceiver，不通过助手写 MCU/CAN。12秒租约、持久恢复记录、开机／启动恢复与手动恢复入口的边界见 [ADR-0010](adr/ADR-0010-hud-exclusive-navigation.md)。原厂桥或系统服务卡死时不能承诺自动恢复时限。

已完成874项测试（871通过、3项既有跳过）、独立审查、构建和下载校验，尚未安装。操作、下载及实车验收项见[独占交付记录](HUD-EXCLUSIVE-20261103.md)。此前人工禁用成功不等于本候选的自动恢复已验证。

## 历史直接输出（2026-11-02 修订）

用户明确选择 CarPlay 为唯一导航来源。HUD 默认开启并保存手动 OFF；删除原车状态监听、UNKNOWN 等待及空闲确认入口，不再因原车事件关闭或重新开始 HUD 输出。本次修订的构建、安装及验证以[直接输出记录](aegis/work/2026-11-02-geely-hud-direct-output/90-evidence.md)为准，以下历史版本不代表当前行为。

数据链路：iAP2 0x5201/0x5202 → Iap2RouteState → GeelyNavigationFrame → GeelyHudTrialPolicy → 显式原厂 AutoNaviReceiver。不是屏幕视频或倒车影像发送。

- Settings/Lease：默认 ON，保存手动 OFF；进程内单一 owner，旧任务不能释放新 owner，失败后同控制器不自动复活。
- Policy：有效路线开始8、导航10001、结束9。用户反馈内容去重无效，已恢复整帧比较；更新时间刷新也重新发送，同一帧的时钟回调不重复发送。5秒有效期、清除后重新开始及最多3次清除重试不变。
- Output：仅固定API18/C3ALFUS/H53.00060和目标包；保留receiver导出/权限/版本及APK、ODEX哈希核验与通道代次检查。不调用USB特权桥或原始MCU/CAN写HUD。
- 原厂APK SHA256：39c5da198ec14a1995772d8920dba7ad6859a4350bdb3c4b913db9d2a650322e；ODEX：ce1779e14124d3d55a8300d847b110a97910bceedcd2c517828d5420956ee154。

原厂开始/结束影响共享导航状态，不是按来源隔离的原子接口。按用户要求不做原车占用保护；原车高德仍可能在后台发送空导航包并覆盖显示，本次不卸载或停止它。强杀、断电和结束失败后的物理恢复仍待验证。

现场曾观测到手机路线约1Hz更新及原厂接收端持续处理零方向/零距离，但这些日志不能独自证明闪烁根因。内容去重修订随后被用户反馈无效并要求撤销；本次恢复原发送方式，不宣称闪烁已解决。

构建从已装默认开启版的精确源码增量生成，保留output3媒体、VPN和USB两秒r2，不混入output4。trial2历史817项回归中814通过、3项跳过；当前修订成绩单独记录，不用历史成绩替代。旧trial2用户“可以了”反馈见[历史记录](aegis/work/2026-11-01-geely-hud-source-state/90-evidence.md)。

## 历史观测版本边界（2026-10-26）

以下各节保留原会话的只观测交付记录和当时未完成项；“当前/本版本”在以下历史内容中指10月26日观测版，现状以上方11月3日章节及[当前状态](GEELY-8600-STATUS.md)为准。

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
