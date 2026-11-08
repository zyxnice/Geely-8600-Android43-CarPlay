# 2026-11-08 用户态 NCM 画面流畅里程碑

本分支保存用户于2026-11-08实车确认“已进入，画面和触控较流畅”的用户态NCM候选，作为后续优化连接重试的基线。它不是连接稳定性已经全部验收的正式版本。

## 已验收的安装包

- 文件：`DiPlay-8600-userspace-ncm-20261108-4c21a50c057a.apk`。
- SHA-256：`4c21a50c057a1603723dc98f1689f8782820b5fe8375a86368a37798bf51326e`。
- 大小：5,338,322字节。
- [下载本次实车包](https://meice-test.oss-cn-shanghai.aliyuncs.com/geely/apk/DiPlay-8600-userspace-ncm-20261108-4c21a50c057a.apk)。2026-11-08已核对OSS上传、公网完整下载和实装包SHA-256一致。
- 包名：`com.shihab.diplay.repair1.geely8600`；versionCode29；versionName `0.2.10-H53-USB3-B`。
- 签名证书SHA-256：`f2ea4c3ab5186fc4aa783c8f09c5a52a32f9fda8c4537283ca8d495e3f6c6d79`。
- 目标车机：星越2019款8600、C3ALFUS、Android4.3/API18、固件 `06.03.08600.H53.00060`。

APK按项目约定存放在测试分发位置，不把安装包、认证资产、签名私钥或完整车机日志提交到Git。恢复这次已验收的二进制时，以此链接和完整hash为准，不能仅凭相同versionName判断。

## 实车条件与结论

- 手机单线直连左侧数据口，右侧空置；车机实际HOST＋CDP。
- 保持1920×720、30fps配置，未降低分辨率；音视频及触控代码保持候选基线实现。
- `GEELY_KERNEL_NCM=false`：使用用户态NCM/VPN路径；`GEELY_USB_RUNTIME_RECOVERY=false`：保持自动系统准备关闭。
- 首轮安装自动重试一次后，于20:39:12.640出首帧；用户反馈比内核NCM版本流畅。
- 再次安装后经历多次自动重试，第4次于20:44:21.400出首帧。用户确认“已进入，画面和触控较流畅”，要求保留此版本，不因单次重试自动回退。
- 20:44:41、46、51三个窗口，触控发送到后续视频帧的平均间隔分别为23、20、40ms，最大46、28、67ms；touchSendMax8ms。视频接收25.8、28.6、27.4fps，shown23.0、25.8、24.0fps。
- 上述touch2frame是触控发送记录到后续视频帧接收的诊断指标，不是Android触摸事件到实际屏幕呈现的完整测量；短时样本也不代表长期无卡顿。

仍未解决或验收：NCM bulk OUT启动准备可能超时、部分重试出现peer EOF、首次连接成功率、重启自动恢复和长期稳定性。本分支不新增重启、网卡启用、供电或角色修改授权。

## 源码及构建身份

分支以`c690d8a47474b06bb430fd16e4213046f4b5715c`为父提交，收录本次候选的独立源码快照，包括当时已有的USB/NCM、缓冲和诊断改动；不从正在继续实验的main工作区取最新未提交内容。

本次车测包并非直接安装一次全量重编译产物：为保持对照单一变量，以已装内核候选`95a3b98597eadb4ac484bf41515656339af6ff2e3ea5559e99e2cc095036e84a`的293个非签名payload为基准，仅替换AndroidManifest.xml中GEELY_KERNEL_NCM的布尔值，再zipalign及使用同一证书签名。DEX、native库和资源均与95a3逐字节一致。

本分支源码的全量重编译产物曾发现legacy USB native库与该实车基线不同，因此**不能宣称普通构建能逐字节复现4c21安装包**。后续重建包须使用新的文件名/hash并重新车测，保留本页原始实车包用于比较。

构建方式仍见[BUILD.md](BUILD.md)，使用显式认证输入及`-PgeelyPerformanceStage=B`。候选准备阶段shared/common测试最终为761/178项、失败0，shared跳过3；首次全量运行有一个依赖全局线程枚举的既有测试失败，隔离及全量复跑通过，未改测试。分支归档仅核对源码/包身份、差异与资料，不把既有构建或短时实车反馈扩写成全面验收。

机器可读安装包记录：[userspace-smooth-20261108.json](milestones/userspace-smooth-20261108.json)。

## 后续源码整合：APP USB 准备

用户随后要求将 Host＋CDP 和内核网卡启用代码合入本分支。
新源码开启 `GEELY_USB_RUNTIME_RECOVERY=true`，继续保持 `GEELY_KERNEL_NCM=false`，保留本页实测的用户态传输与音视频/触控实现。
网卡UP代码只在显式内核路径执行，默认用户态连接不调用。已Host/CDP或网卡已UP时跳过对应变更，
不改系统默认/persist/ADB设置；Host运行态切换仍可能断开该口有线ADB。现场未操作、未安装新包。

以上是本分支后续源码行为，不改变本页原始安装包hash、两个关闭的metadata及实车反馈；
新构建需独立实车验收。详见[整合记录](aegis/work/2026-11-08-smooth-usb-integration/20-checkpoint.md)。
