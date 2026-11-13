# DiPlay · 吉利星越 2019 款 8600 车机专用

> **当前 USB 稳定 A 版本（更新于2026-11-12）：用户态 NCM＋Host/CDP 自动准备。** 基线为 `milestone/usb-smooth-userspace-ncm-20261108`，A包源码 `3c6cc29`、APK `c0628c96dede`。用户确认其他功能一切正常；**CarPlay 通道打开及连接建立耗时过长仍未优化完毕。** [版本身份、下载与状态](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/blob/main/docs/USB-HOST-CDP-ACCEPTANCE-20261108.md)。


本项目仅面向**吉利星越 2019 款、8600 版本原厂车机**，维护 Android 4.3 / API18 环境下的 CarPlay 适配，当前重点为 USB 连接、音视频播放、触控响应、连接恢复与 HUD 导航。

仓库已脱离原上游独立维护。博瑞 H52、其他吉利车型、其他年份或车机版本、BYD 及通用 Android 车机均不在本项目支持范围内。即使系统版本相同，也不能据此认定兼容。

## 适用范围

| 项目 | 当前范围 |
| --- | --- |
| 车型 | 吉利星越 2019 款 |
| 车机版本 | 8600 |
| 已记录硬件 | GEELY NL5QJ，BOARD=C3ALFUS |
| 已记录固件 | `06.03.08600.H53.00060` |
| 系统 | Android 4.3 / API18 |
| 当前适配重点 | USB CarPlay、音视频、触控、重连与 HUD 导航 |
| 车测应用包名 | `com.shihab.diplay.repair1.geely8600` |

上述硬件与固件来自已有现场记录，不代表所有标为 8600 的固件均已验证。请先核对车型、年份、车机和完整固件号。

## 当前稳定版本：A 版本（2026-11-12）

用户确认：`milestone/usb-smooth-userspace-ncm-20261108` 所属的 USB 流畅版本作为当前 USB 连接方式的稳定基线；**CarPlay 通道打开及连接建立耗时过长，优化尚未完成；其他功能一切正常。** 本结论对应用户当前 USB 实车使用反馈。

| 项目 | 当前说明 |
| --- | --- |
| 版本称呼 | **A 版本：用户态 NCM＋Host/CDP 自动准备** |
| 基线分支 | [milestone/usb-smooth-userspace-ncm-20261108](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/tree/milestone/usb-smooth-userspace-ncm-20261108) |
| A 版本源码 | [3c6cc29](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/commit/3c6cc2930c686e820bf56de86fd9a1556862f7d6)，已收录于 main |
| A 版本安装包 | [c0628c96dede](https://meice-test.oss-cn-shanghai.aliyuncs.com/geely/apk/DiPlay-8600-smooth-host-cdp-3c6cc29-20261108-c0628c96dede.apk)；完整身份见[版本记录](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/blob/main/docs/USB-HOST-CDP-ACCEPTANCE-20261108.md) |
| 已知待优化 | CarPlay 通道打开、建立连接等待过长；不能表述为已解决或首次连接必成功 |
| 其他功能 | 用户确认正常 |

“稳定版本”是当前 USB 使用基线的定位，不将无线、其他车型、可选内核 NCM 或历史独立维护试验纳入此次确认。A 版本固定对应上述源码和 APK，不指 workspace52，也不沿用早期实验包的 A 编号。

### 分支与安装包身份

2026-11-12核对：GitHub里程碑分支的应用代码仍是原始 `c51d09c` 快照；A版本的Host/CDP自动准备整合提交为 `3c6cc29`，已在 `main` 中。本次只更新说明和状态，未把两份源码视作同一版本。需要构建与A包一致的应用代码时，以 `3c6cc29` 为准；原始 `4c21a50c057a` 包继续按历史记录保留。

## 历史状态（2026-11-04）

| 项目 | 最新结论 |
| --- | --- |
| 讯飞仅按键候选（11月4日） | 已定向安装并确认重启后加载，空闲录音停止、CPU采样约1%～2%；用户报告唤醒词仍有回复，与自动测试存在时间交叉，**仅按键验收尚未通过**。[状态、源码与恢复说明](docs/IFLYTEK-BUTTON-ONLY-20261104.md) |
| HUD 闪烁 | 行驶隔离确认原车高德的零值数据与 CarPlay 竞争覆盖；禁用后上下两处均不闪。用户随后自行卸载高德，反馈当前不再闪烁。 |
| 卸载原车高德后讯飞重试 | 讯飞约每 2 秒尝试绑定缺失的导航服务。已装独立兼容 APK，实车重启后讯飞自动绑定，连续 65 秒未再出现该重试。[解决方案与源码](docs/OEM-APP-REMOVAL.md#讯飞导航服务) · [下载独立桥接 APK](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/releases/download/navigation-bridge-v1.0-20261103/Geely-NavigationBridge-1.0-20261103-66f37f2d008a.apk) |
| 卸载原车会员中心 | 在线移除后，系统仍反复尝试拉起原常驻服务，产生额外后台工作。实车重启后采样未再观察到会员中心启动，CPU 影响尚无同条件对照。[排查与处理记录](docs/OEM-APP-REMOVAL.md#会员中心) |
| 当前源码 | 收录 USB 流畅度里程碑：PCM、USB、TLS 缓冲复用及帧时序诊断；HUD 独占保持撤销。 |
| 最近核实的车上版本 | `tls-read-scratch`，SHA-256 前缀 `7ef58d64cb92`；用户认可大部分滑动明显改善，仍偶发长停顿。[里程碑与下载](docs/USB-SMOOTHNESS-MILESTONE-20261104.md) |
| 导航声音 | 保留原厂媒体23／导航11，用户已确认导航出声及方向盘调节声音；本次撤销未改音频逻辑。 |
| 已撤回候选 | 独占包 `44e915ea0b0c` 不再推荐安装；其测试与交付证据仅作历史记录。 |
| 尚待实车验证 | USB重复启动和长测、VPN重启授权问题仍保留。 |

此前 HUD 调整（2026-11-04）：用户卸载原车高德并反馈 HUD 不闪后，已撤销独占模式，恢复独占前的 HUD 行为，不再禁用或恢复原车应用。此次流畅度里程碑继续保留该行为、媒体23／导航11及既有 USB 修复。

[完整状态与历史记录](docs/GEELY-8600-STATUS.md) · [HUD 使用说明](docs/GEELY-8600-HUD.md)

11月1日至2日的 trial2、音频恢复和各轮排查记录保留在状态文档的历史部分，不代表已撤回独占包通过实车验收。无线连接及其他车型能力仍未验收。

## 后续重点：WiFi 连接

2026-11-13 实车对照确认原厂蓝牙 A2DP 连接参与 WiFi 音乐无声；自动隔离试验包的音乐和暂停重播已获用户确认。本次仅保留蓝牙媒体隔离修复，撤回其他排查调整，精简后的组合尚待实车回归，见 [WiFi连接状态](docs/WIFI-CONNECTION-STATUS.md)。

A版本继续作为当前USB稳定基线；其CarPlay通道打开与连接建立耗时过长仍是保留问题。后续WiFi优化需单独验证，并回归已确认正常的USB功能。

## 下载、安装与反馈

**[GitHub Release：A版本 · 2026-11-12](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/releases/tag/v2026.11.12-usb-stable-a)** · [下载A版本APK](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/releases/download/v2026.11.12-usb-stable-a/DiPlay-8600-smooth-host-cdp-3c6cc29-20261108-c0628c96dede.apk)。标签：`v2026.11.12-usb-stable-a`；应用代码对应已验证的`3c6cc29`，标签另包含最新状态文档。

当前 USB 稳定版本使用 [A 版本 APK](https://meice-test.oss-cn-shanghai.aliyuncs.com/geely/apk/DiPlay-8600-smooth-host-cdp-3c6cc29-20261108-c0628c96dede.apk)，完整 SHA-256 与安装身份见[版本记录](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/blob/main/docs/USB-HOST-CDP-ACCEPTANCE-20261108.md)。CarPlay 连接建立较慢仍在优化，其他功能按用户反馈正常。此前安装包和[11月4日记录](docs/USB-SMOOTHNESS-MILESTONE-20261104.md)仅用于历史追溯；[HUD独占包](docs/HUD-EXCLUSIVE-20261103.md)仍已撤回。

公开源码树已移除旧 H52/P21 安装包，保留其历史说明与源码快照供追溯；不改写 Git 历史。

安装与使用步骤见[星越 8600 安装说明](docs/INSTALL.md)。本项目仍在适配与排查中，安装前请阅读对应版本的限制和回退说明。

问题请提交至[本仓库 Issues](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/issues)，附上车型年份、完整固件号、APK 版本或文件名、iPhone/iOS 版本、连接方式和复现步骤。仅受理上述目标车型与车机范围的问题；诊断文件请先检查，勿公开凭据及配对记录。

## 开发与技术记录

- [文档导航：当前说明与历史记录](docs/README.md)：按用途区分现行入口、候选证据和历史计划。
- [当前版本与验证状态](docs/GEELY-8600-STATUS.md)：已装候选、功能提交、实车反馈与未完成事项。
- [HUD 接入与使用说明](docs/GEELY-8600-HUD.md)：默认开启、导航刷新与试验边界。
- [USB 问题与验收清单](docs/GEELY-8600-USB-ACCEPTANCE-CHECKLIST.md)：后续 USB 优化与验证的统一入口，包含待解决问题、暂定标准、验证顺序和结果表。
- [构建说明](docs/BUILD.md)：源码构建、8600 车测变体和打包边界。
- [8600 USB 排查总记录](docs/GEELY-8600-HANDOFF.md)：2026-10-27 的现场记录，按日期理解结论。
- [8600 USB2 历史适配记录](docs/GEELY-8600-USB2.md)：USB 自动准备过程与当时验证边界。
- [H52 历史版本记录](VERSION-HISTORY.md)、[API18 迁移审查](MIGRATION-REVIEW.md)、[上游变更记录](CHANGELOG.md)：用于追溯来源，不作为星越 8600 的功能或验收清单。

仓库保留的其他车型代码、上游文档和历史版本记录属于继承实现与技术资料，不构成继续支持这些车型的承诺。后续开发以星越 2019 款 8600 的实际需求与实车证据为准。

## 来源与许可

代码来源包括 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) 与 [xikai6282/DiPlay-Geely-Android43](https://github.com/xikai6282/DiPlay-Geely-Android43) 的既有实现。独立维护不改变来源归属，继续遵循 [GPL-3.0](LICENSE) 与[第三方许可声明](docs/THIRD_PARTY_NOTICES.md)。

本项目不是 Apple 或吉利官方产品，未获得 Apple CarPlay 认证。认证资产及签名边界见 [SECURITY.md](SECURITY.md)；公开源码不包含独立认证或 Android 签名私钥。

