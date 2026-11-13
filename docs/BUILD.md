# 吉利星越 2019 款 8600 开发与构建

本仓库独立维护星越 2019 款 8600 的 Android 4.3 / API18 CarPlay 适配。`mobile` 中的 `geelyUsb` 为车测变体，包名 `com.shihab.diplay.repair1.geely8600`；保留的 automotive、samples 及其他车型实现不构成本项目支持承诺。以下构建成功均不等同于实车验收。

## 8600 车测变体

2026-11-13：[Wi-Fi 蓝牙隔离精简测试版](WIFI-CONNECTION-STATUS.md)源码为 `da26e77`，
使用下述原有 B 变体构建，不再提供 `geelyWifiTrial`、`geelyWifiQuiet` 或日志对照构建开关。
本次 Release 是不含认证资产的覆盖测试包，构建时不设置 `DIPLAY_AUTH_ASSETS_DIR`，
仅适用于已保留运行时认证身份的设备；不能把它当作后文所述独立安装包。
版本字段沿用 `0.2.10-H53-USB3-B`，请用文件名和 SHA-256 区分产物。

2026-11-04：HUD 独占模式已按用户要求撤销，当前流畅度里程碑保留独占前的 HUD 行为、媒体23／导航11及既有 USB 修复；不再禁用或恢复原车应用，不要求重新安装高德。

当前源码在既有音频及 USB／媒体行为上收录 [USB 流畅度里程碑](USB-SMOOTHNESS-MILESTONE-20261104.md)的缓冲复用及帧时序诊断，实际安装身份见[当前状态](GEELY-8600-STATUS.md)。构建专用 B 变体需显式选择 B：

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleGeelyUsb '-PgeelyPerformanceStage=B'
```

shared全量lint仍有既有问题，不能将构建成功表述为全量lint通过。HUD真实显示与异常恢复依赖独立实车验收。11月1日trial2的817项测试和默认关闭行为保留在[历史状态](GEELY-8600-STATUS.md)，不描述当前源码。

### 2026-11-08 main 收录的画面流畅版本与 USB 准备

main 合入 `milestone/usb-smooth-userspace-ncm-20261108`，保留已归档的用户态 NCM 传输、音视频及触控实现，
合入 APP 运行时 Host（0）＋CDP 准备和受限内核网卡 UP 代码。
`GEELY_USB_RUNTIME_RECOVERY=true`，`GEELY_KERNEL_NCM=false`；后者继续选择用户态 NCM/VPN，
因此新增内核网卡 UP 功能在本分支默认路径不执行，不会扫描并启用其他网卡。
将来显式启用内核分支时，它才对当前绑定的 iPhone cdc_ncm 网卡按需 UP；该开关不是用户态连接的前置条件。

正常有线初始发现按需准备 Host＋CDP；已满足状态就继续发现，不调用角色准备 helper。
开机启动仅打开APP，自动进入有线准备还取决于既有自动连接和保存的有线连接类型；未进入该流程时不操作端口。
活动配置6手机保持原链路，允许其原有SDP/CDP，不为改变充电模式重枚举。移除旧的空发现定时断电路径；
保留原有精确开机role=-1故障恢复和有限重试约束。普通构建和不匹配API18/C3ALFUS/完整H53的设备不启用准备。
不改系统默认、persist或固件启动文件；运行时切Host仍会移除该OTG口的USB ADB function，不能把默认未改等同于有线ADB不中断。

内核备用路径不写死 `usb1`，在启用前后匹配USB枚举全身份、配置6、固定控制器、cdc_ncm、ifindex、MAC和接口alternate。
已UP跳过，DOWN每绑定最多尝试一次固定 `busybox ifconfig <当前网卡> up`，回读后等待本接口可用link-local IPv6。
失败/取消/关闭不DOWN，不设置IP、路由、DNS或IPv6开关。6秒租约、调用端最多等待7秒、helper看门狗8秒，
挂起Binder保持单飞；取消无法撤销已发出的ioctl，最终校验到按名称操作仍有有限竞态。

源码 `3c6cc29` 已构建为独立认证包 `c0628c96dede` 并实车覆盖安装，用户随后确认验证后无明显大问题；
安装身份与验收范围见[当前版本记录](USB-HOST-CDP-ACCEPTANCE-20261108.md)。应用代码保持该实测提交，main 后续只补文档。
这不改变历史实车包 `4c21a50c057a` 的身份和验证范围。
原安装包记录及下载保持在[11月8日里程碑](USB-SMOOTHNESS-MILESTONE-20261108.md)，
新代码的本地证据和未覆盖项见[整合记录](aegis/work/2026-11-08-smooth-usb-integration/20-checkpoint.md)，
内核UP边界见 [ADR-0012](adr/ADR-0012-kernel-ncm-interface-preparation.md)。首次连接成功率、冷启动及长期稳定性仍需独立验收。

使用仓库内 Gradle wrapper；以下命令在 Windows PowerShell 仓库根目录执行：

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleGeelyUsb
```

普通构建未指定本地认证资产时，产物不具备独立连接手机所需的身份。需要车测时，先在本地将 `DIPLAY_AUTH_ASSETS_DIR` 指向已授权的认证资产目录，再构建；不得将认证文件提交到仓库。基础变体版本后缀为 `-H53-USB2`，可通过 `-PgeelyPerformanceStage=B` 选择 USB3-B 变体；A/B/C 是候选实现标识，不代表验收等级，未指定时为 `off`。

产物目录为 `mobile/build/outputs/apk/geelyUsb/`。交付前核对 APK 中的包名、版本、签名、API18/ARMv7 兼容与所选本地认证输入。覆盖安装要求包名和签名一致；发布包及校验值以[本仓库 Releases](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/releases)说明为准。

## 继承的通用构建与打包说明

以下保留源码构建、认证资产与签名边界；通用 debug/release 不替代上面的 8600 车测变体。

Requirements: JDK 25, Android SDK 37, NDK 28.2.13676358 and the included Gradle wrapper.

## Source and CI builds

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

The resulting source-only APK contains no accessory identity. Standalone CarPlay requires runtime authentication provisioning. Tests generate synthetic identities at runtime; no test private-key files are tracked.

## Local release packaging

Provide an external asset directory using `DIPLAY_AUTH_ASSETS_DIR`. The directory must contain exactly the intended runtime files under `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. Neither file belongs in Git. The build permits those two files only when this explicit input is set and rejects unexpected credential containers elsewhere in APK assets.

Set `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` locally for your Android signing key. Never commit these values or the keystore. Different signing keys cannot update an existing project-signed installation.

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

Output: `mobile/build/outputs/apk/release/mobile-release.apk`. The release APK deliberately contains the experimental identity described in the notices; it is extractable by recipients. The separate Android signing key is not included. The retired build-beta.py helper is not used; this Gradle workflow uses explicit environment inputs.

The public release source archive corresponds to the tagged source and excludes runtime identities, signing keys, local configuration and build output.

## Standalone car-test APK

Use `:mobile:assembleStandaloneDebug` for a test APK that must connect to an iPhone:

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

This task refuses missing or empty runtime inputs. `assembleDebug` remains an identity-free
source/CI build when the explicit asset input is absent; do not install that output as a
standalone car-test package. Before delivery, verify both `assets/offline-mfi/identity.pk8`
and `assets/offline-mfi/certificate.p7b` in the APK against the selected local inputs.
Update the existing test app without uninstalling it to preserve its settings.
