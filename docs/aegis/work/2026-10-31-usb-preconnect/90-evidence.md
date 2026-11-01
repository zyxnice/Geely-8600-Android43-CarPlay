# 本地交付与后续验收

2026-10-31。本地实现、回归、独立复核及候选打包完成；已按用户授权实车同签覆盖r2并核对已安装SHA，实车连接故障尚未验收；未提交或发布。

## 已核验

- 用户事实：每天首次启动车辆，先插 USB、后启动 DiPlay 无法识别；保持应用打开重新插拔可连接。
- 代码事实：旧初始发现已主动扫描，不依赖插入广播；端口状态正常而无设备时只轮询，缺少重插类恢复。新恢复不解决已经发现 Apple 之后的权限、配置或握手故障。
- 兼容层增加空发现2秒后单次恢复；helper 两次验证 Host 请求配置与实际配置、角色、SDP、供电、保护及总线1为空，满足时断电500ms后供电。任何已观察到的下游节点均阻止恢复。停止租约、单飞、超时、故障保护和恢复供电沿用原有边界。
- 独立 agent 审查实际 diff，发现并修复配置尚未生效窗口及普通准备预算误拦截枚举恢复的问题；复读未发现新的阻塞问题。现有字段、公共应用接口、无线及媒体流程未改。
- 最新工作区 shared/common 单测任务成功；USB策略25项全部通过。已核对最新实际 diff 的组织、注释与调用方；git diff --check通过。原有未提交 VPN/媒体/文档保留，不提交混合工作区。
- 候选以 `output/vpn-restore-20261030/source-output3` 为基线，覆盖本次5个USB源码及1个测试文件；逐文件比较整个源码树（排除构建缓存/私有资产）确认仅这6个文件不同。保留 output3 媒体和 VPN 修复，未混入 output4 诊断。
- 候选完整 shared/common 回归771项：768通过、3项既有host-JNI跳过、0失败/错误。完整 assembleGeelyUsb 成功；独立API18 helper编译与DEX035打包通过。
- 包体校验：包名、versionCode29、H53-USB3-B、minSdk18、ARMv7、v1/v2签名、metadata、helperDEX、B原生库、两项认证输入哈希及与output3-vpn基线同签替换身份均通过。

## 成果

APK：`D:/geely/output/usb-preconnect-20261031/DiPlay-8600-USB3-B-output3-vpn-usb-preconnect-2s-r2.apk`

SHA-256：`57da4a0fcf8c604797ce5ab38715c233582c5be0fc184da3136f752859bab1b6`

同日用户反馈8秒太长后改为2秒，原8秒APK保留但已被本候选替代；文件名含 `-2s` 区分。同步移除模式准备5秒冷却对已就绪端口枚举恢复的阻挡，模式异常时仍保留5秒限制。新边界1999/2000ms等效时间测试与完整回归重新执行，仍为771项、768通过、3既有跳过；独立复核本轮两处生产增量通过。2秒从首次空发现计时，不代表启动后2秒保证连接；还有轮询、助手启动、500ms断电及手机握手耗时。

现场发现总线1始终有根hub接口 `1-0:1.0`，原空总线保护会误把它当作外接设备，因此此前8秒及2秒候选均被r2替代，不应装车。r2仅排除 `1-0:` 根接口，仍保护 `1-1` 及其所有子节点和接口；新增对应回归，772项、769通过、3既有跳过。独立复核这一修正通过，包体校验重新通过。

## 16:19起实车只读诊断

用户已上车并明确线已插着，DiPlay无法识别，与原始场景一致；随后用户去打电话，要求继续后台工作。保持原现场，不拔插、不重启应用/系统、不停止原厂服务、不手动切USB配置、不安装候选。

身份：API18/C3ALFUS/06.03.08600.H53.00060，boot_id `bd42e87a-3ae4-4500-8464-b3e7258c7e09`；实时已装APK设备端busybox SHA确认 `779329b0df129ceca5788976138c17deede3117198e0209f20b9adfd59e6314c`，是已归档output3-vpn版本。全量APK拉取90秒超时，部分本地副本不是有效APK，不使用；改用设备端哈希成功确定身份。

USB：config/state均 `accessory,ncm,adb`、role1、charging=cdp、power=`normal, power on`、fault=`working fine.`；bus1只有根hub接口，Framework列表未见Apple。屏幕实际错误为 `USB自动准备失败: runtime_no_verified_response`，应用内部日志显示在初始runtime prepare即失败，尚未到枚举恢复。

固定只读OEM探针 `id`、helper文件ls及无参数helper检查均返回一字节空结果；无参数helper在参数校验处退出，不会执行USB写。进一步查询：`usbprotect` Binder存在，`native-commond`进程PID2341、init状态running且二进制SHA与本地固件相符，但 `service check COMMOND_Server` 两次返回not found，服务列表中亦未列出该名字。固件客户端确实按此名请求底层服务。现有证据支持底层命令桥当前不可用这一近因，不能确定为什么原厂进程未注册服务，也不能将进程running当成功。

证据：`D:/geely/output/usb-preconnect-20261031/vehicle/161942/` 的 screen.png、usb-manager.txt、usb-nodes.txt、oem-id.txt、helper-readonly-probe.txt、services.txt、processes-latest.txt等。r2仅完成本地准备，不能解决尚未可用的命令桥。下一步待用户电话结束，告知此真实阻塞，先讨论最小恢复/复现操作；任何原厂服务重启不自行执行。当前本机调试监督PID34932/SSH39716为本轮快照，后续需实时核对；未修改107服务端。

## 后续拔插对照与授权服务恢复尝试

用户按提示保持应用打开完成一次拔插，反馈仍等待连接。抓屏与日志确认仍是 `runtime_no_verified_response`；原厂桥 textBytes=1，USB准备失败，未进入设备枚举恢复。本轮拔插不能记为恢复成功。调试通道有一次短暂offline，定向重连后device恢复；没有重启ADB server。

用户明确同意“只尝试重启原厂命令服务一次，不重启整台车机”。执行前再次核对精确平台、进程PID2341、service check not found。仅执行一次 `setprop ctl.restart native-commond`，ADB命令退出0但2秒后PID仍2341、init仍running、COMMOND_Server仍not found、USB仍role1/ADB配置，故没有实际重启证据，不得称恢复成功。init/logcat/dmesg未获得明确拒绝原因，不能把权限原因写成已证实根因。未自动升级ADB权限、杀系统进程、重启整车机或改持久配置；当前入口未能完成这个单服务恢复，后续需用户配合正常车机重启或新的明确授权与可用恢复入口。

证据保存在 `vehicle/replug-compare/`：before-command-service-restart.txt、command-service-restart-result.txt、after-command-service-restart.txt、screen-after.png、logcat.txt等。

## 用户授权先覆盖r2、随后自行重启

用户明确要求先将准备的安装包替换到车机，再自行重启测试。已核对当前包SHA仍779329b0...e6314c、userId10062、/data空闲7.9GB，目标r2同签、包名/versionCode一致，回退包已本地归档。未清数据、未卸载。

通过本地逐字节验证的ZIP分段重建方案传输目标r2（16528428字节），gzip增量7248601字节。累计报告上传3932160字节后，某分片push60秒超时，通道不再响应设备shell；定向ADB断开/重连后返回offline。未运行任何组装或pm install命令，安装目标尚未达到，旧版未被本次替换。分片状态在设备端尚未完整校验，不能仅凭本地上传进度认定其有效。

已准备顺序续传：`D:/geely/analysis/upload_usb_preconnect_r2.py` 先逐片设备SHA校验、仅补缺失或不匹配的任务分片，任何失败立即停止，不并发push；再校验组合gzip，随后独立执行精确重建与整APK SHA核对，匹配57da4a0f...bab1b6后才允许pm install -r，最后回读已安装APK SHA与UID。当前需用户恢复车机ConnectBot保存主机的连接，手机热点保持开启；先不要重启车机。没有操作107服务端或其它系统服务。

## 用户重启后重新接入：r2已覆盖安装

用户关车后重新启动车辆，并要求继续、再试一次。最后一次定向ADB重连成功，实时板型与固件匹配，新boot_id为 `e5be292c-bc95-44e3-bb48-923491549f7f`，`service check COMMOND_Server` 返回found。说明这次重启后该服务可查到，不能据此认定其此前缺失的唯一根因或每天冷启动一定正常。

设备原包SHA仍为779329b0...e6314c。顺序续传先校验既有分片，再补齐至7248601字节，组合gzip SHA为979bc20773eb5c04e975a0cf5d44976fc6275d4a9125ed166315d7650a2c095e。精确重建16528428字节APK，设备端SHA与本地r2完整一致。安装脚本再次守卫板型、固件、本次boot、旧包及新包SHA；安装前备份旧包并核对SHA。

`pm install -r` 返回Success，随后 `pm path` 回读 `/data/app/com.shihab.diplay.repair1.geely8600-2.apk`，该实际安装文件SHA为 `57da4a0fcf8c604797ce5ab38715c233582c5be0fc184da3136f752859bab1b6`；userId10062、dataDir保持，versionCode29。没有卸载、清数据或由Codex重启车机。回退包已留在设备 `/data/local/tmp/DiPlay-output3-vpn-before-usb-r2-20261031.apk`，SHA779329b0...e6314c与本地基线一致。

原先语音转述中的“装好了”在此次Success及安装文件SHA核对前均不成立；上文旧版未变/等待续传是当时历史状态，现在已被本节新鲜证据更新。安装完成不等于USB修复通过；尚未打开新包验证，按用户计划待其正常重启后恢复ConnectBot，再测试先插USB、后开DiPlay的完整连接。

新证据：vehicle/upload-r2-resume.log、assembly-r2-result.txt、install-r2-result.txt、package-after-install.txt、installed-r2-sha256.txt、reconnected-after-reboot.txt。后续若更新本节，应继续区分安装、服务可用、实际自动恢复与用户音画触控验收。

候选源码、build.log、apk-verification.log、receipt.json 与 APK 同目录。工作区最新回归日志：`D:/geely/output/usb-preconnect-workspace-tests-20261031.log`。候选与已归档源码中的5个USB生产文件、1个测试均已同步到本次最终修改。

## 未完成与下次最小验证

没有本次失败时的冷启动实时日志，不能认定唯一根因。500ms临时断电是否足以替代物理重插、应用调用OEM桥的完整行为及真实冷启动效果未验。空列表不能证明线已连接；未接手机的等待也可能触发一次。总线检查与供电写不是原子操作，仍可能打断检查后刚出现的新枚举；不可宣称完全无竞态。

现场先核对实际车机/固件和已装APK SHA，确认安装范围后同签覆盖，不卸载清数据。回退使用归档 output3-vpn-fix，SHA `779329b0df129ceca5788976138c17deede3117198e0209f20b9adfd59e6314c`。

1. 正常首次启动车辆，先插已解锁iPhone，再打开DiPlay，不拔线；记录完整首帧耗时、USB发现日志及 enumerationRecovery/result。
2. 若仍失败，在重新插拔前保存日志，区分一直空发现、device-preserved/not-ready，以及已发现后的权限/握手失败。用户手工重插后成功不能记作自动恢复通过。
3. 相邻场景验证后插线、普通拔插、软件重开，以及已有正常播放不受影响；分别确认音乐、画面与触控。冷启动首次VPN恢复仍按既有记录单独验收。

交付状态：本地候选已准备；目标“每天首次先插线也能自动识别”待实车验证。不存在本轮新增开机服务、持久USB设置或任意shell入口；原有准备路径继续保留，空窗恢复失败不能冒充设备已连接。
