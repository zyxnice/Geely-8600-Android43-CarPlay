# 用户态 NCM 与 APP Host/CDP：2026-11-08 实车确认版本

本页记录 main 收录的实车版本。用户在安装后明确反馈：“这个版本我验证过了，没有什么太大问题”，并要求合入 main 推送。
该反馈按用户的实际验证原意保留，不等于所有冷启动、重复拔插或长期稳定性项目已逐项验收。

## 安装包与源码

- 源码：`3c6cc2930c686e820bf56de86fd9a1556862f7d6`；来自 `milestone/usb-smooth-userspace-ncm-20261108`。
- APK：`DiPlay-8600-smooth-host-cdp-3c6cc29-20261108-c0628c96dede.apk`，5,601,931字节。
- SHA-256：`c0628c96dedeb56d1cb5c80f702cfc797527fabc44a1b72134a298f0e3638859`。
- [下载已实装核验的测试包](https://meice-test.oss-cn-shanghai.aliyuncs.com/geely/apk/DiPlay-8600-smooth-host-cdp-3c6cc29-20261108-c0628c96dede.apk)。
- 包名 `com.shihab.diplay.repair1.geely8600`；versionCode29；`0.2.10-H53-USB3-B`。
- 签名证书SHA-256：`f2ea4c3ab5186fc4aa783c8f09c5a52a32f9fda8c4537283ca8d495e3f6c6d79`。
- 目标：星越2019款8600，C3ALFUS/API18，固件 `06.03.08600.H53.00060`。

包从干净3c6cc29源码以原显式认证输入完整构建；main在此应用代码之上只补本次确认文档。
APK、认证材料、签名私钥和原始现场日志不提交Git。恢复或比较本包以完整hash为准，不能仅凭相同versionName判断。
机器可读记录：[userspace-host-cdp-20261108.json](milestones/userspace-host-cdp-20261108.json)。

## 本版行为

保留用户态NCM/VPN和画面流畅里程碑的音视频、触控、缓冲及USB关闭/重连实现。
`GEELY_USB_RUNTIME_RECOVERY=true`，APP进入正常有线发现时按需准备Host＋CDP，已就绪跳过helper；
旧空发现定时断电逻辑已移除，原精确开机role=-1恢复保留。活跃配置6的SDP/CDP链路不为改变充电模式重枚举。

`GEELY_KERNEL_NCM=false`，因此虽已收录受限网卡UP代码，本包用户态连接不执行内核网卡启用。
该内核功能仍保留固定平台、枚举全身份、ifindex/MAC与接口校验，仅在显式内核分支按需UP；不能将本包反馈当作其实车验收。
不改系统默认/persist/固件启动文件。Host运行时会移除该OTG口的USB ADB function，默认未改不代表任意有线ADB均不中断。

## 已有证据

- shared/common共949项，失败/错误0，3项既有跳过；专用geelyUsb与普通debug构建通过，合入前再次执行既定检查通过，未改测试或跳过失败。
- 现有完整包校验核对API18、v1签名、同签覆盖身份、ARMv7、helper DEX035和两项显式认证hash；新旧认证材料hash相同。
- OSS上传返回200，匿名下载和车机端文件完整hash相同；`pm install -r`返回Success，实际安装文件再核对为上述完整hash。
- 21:19:20完成覆盖更新，UID仍10036、原dataDir及首次安装时间保留；没有卸载、清数据或重启。
- 21:20:43.570从正常USB入口请求prepare，21:20:44.740返回prepared，21:20:46.810后续发现为ready；回读role0/CDP、供电正常、无故障。
- 系统默认USB配置与持久ADB端口保持原值，网络ADB仍在线；随后CarPlayHostActivity运行，视频decoded/rendered及音频播放进度有现场记录。
- 用户随后完成自己的实车验证，报告没有什么太大问题。本次main整合有独立实际diff复核，应用代码与用户已验证提交一致。

## 保留的边界

现场日志存在attempt=2，本版不承诺首次连接必成功。首次成功率、完整重启/重复拔插、长时间音画触控稳定性仍按原清单验证。
没有从短时帧率或触控诊断推导主观体验百分比，也没有宣称内核网卡UP已实车通过。
此前 `4c21a50c057a` 的原始流畅里程碑及其JSON不修改，见[原记录](USB-SMOOTHNESS-MILESTONE-20261108.md)。
本次按用户指令只合入并推送main，不新增Release、标签或车机操作。安装与回退规则沿用 [INSTALL.md](INSTALL.md)。
