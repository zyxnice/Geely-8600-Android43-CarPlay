# 8600 车机直连调试通道与断线排查

日期：2026-10-28。目标：解除调试对 iPhone Termius 前台运行的依赖，并核验断线恢复。当前不改变 DiPlay 的 USB 或性能实现。

## 证据与原因边界

- 旧链路：电脑本地 15555 → 107 本机 15555 → iPhone Termius → 车机 172.20.10.9:5555。
- 服务器实时记录：电脑 SSH 从前一天 20:18 持续存在；手机会话在 07:43、08:00、08:11、08:16 等时段关闭/重建。断线集中在手机中转这一段，不能把每一次断线都归因于同一个原因。
- iOS 后台挂起与“切离 Termius 后断线”相符，但尚未逐次取得手机侧日志。服务端保活不能让被系统挂起的手机应用继续处理网络。
- 旧账号 caradb_1027 的 Account expires 为 2026-10-29。它是另一个确定的后续失效条件，不作为今天断线的原因。
- 原 sshd 配置 ClientAliveInterval=0；无周期 SSH 层探活，不利于及时清理无响应的旧连接。

## 已落地配置

新链路：电脑 127.0.0.1:15556 → 107 127.0.0.1:15556 → **车机 ConnectBot** → 车机 127.0.0.1:5555。iPhone 只提供热点上网。

车机安装官方 ConnectBot v1.9.13 OSS，包名 org.connectbot，minSdk16，适配本车 API18。新版本最低系统更高，不能直接替换。

- APK 6808620 字节；SHA256 `81057c26d1d69fea1a72a8bcf2fa939f1a8d531a296af578e326b737e364988a`；本地和车机副本一致，安装返回 Success。
- 仅端口转发，不启动 shell；保持连接开启、后台保持连接开启、Wi-Fi 活动保持开启。
- 已导入专用 RSA3072 密钥并指定给该主机；临时 /sdcard/car_relay_20261028.pem 已删除。私钥不进入仓库。
- 远程转发名称 ADB-direct，源端口15556，目标127.0.0.1:5555。
- 车机主机身份使用经 Cloud Assistant 独立核对的 Ed25519 指纹 `96:7c:58:19:d7:24:66:f1:56:c7:cb:0e:e1:96:c9:23`；电脑使用已核对的 ECDSA host key。

107 对象由阿里云 CLI 唯一定位为 cn-shanghai / i-uf6gvgd2gh379yosu9bd，Cloud Assistant 在线。全部服务器管理通过 Cloud Assistant 完成；SSH 仅承载本任务数据转发。

- 创建专用账号 carrelay_8600，nologin、锁定密码、无账号到期日；home/.ssh/authorized_keys 由 root 拥有。旧账户及其授权保持不变。
- 专用密钥禁止 shell、PTY、agent、X11；电脑密钥仅允许 direct TCP 到127.0.0.1:15556。车机密钥 direct TCP 限于127.0.0.1:1，实际使用 reverse forwarding。
- OpenSSH7.4 不支持按 key 限定 reverse 监听端口；GatewayPorts=no 限定服务器回环接口。不能声称该版本已做到只允许一个 reverse 端口。
- 仅 Match User carrelay_8600 增加 ClientAliveInterval15 / ClientAliveCountMax3，禁密码与键盘交互认证。候选配置 sshd -t 通过，root/caradb_1027 的 effective 配置与修改前相同，reload 后 active。未重启服务。
- 原 sshd 配置备份 /etc/ssh/sshd_config.before-carrelay-20261028。此后有其他人改配置时不得盲目覆盖回滚，应只撤销本次标记段。

## 已完成验收

1. 新通道真实 ADB shell 返回 CAR_DIRECT_ADB_OK、C3ALFUS 与持续增长的 uptime。
2. 用户明确停止 Termius 并退出；旧15555显示offline，新15556显示device，实际 shell 继续返回。随后切回 DiPlay，ConnectBot 服务 isForeground=true，后台读命令仍正常。
3. 08:31:00 定向终止持有15556的专用 sshd 子进程14754；08:31:03服务器记录同一车机专用RSA密钥重新认证，新监听进程22751。未操作手机或车机，随后真实 ADB shell 恢复。
4. 用户完成关闭蜂窝数据再恢复操作后，服务器记录08:32:12旧会话关闭、08:32:18/22车机密钥经变化后的出口IP重新认证，新监听25263；08:33:00真实ADB命令返回。恢复后没有打开Termius。操作开始/恢复的精确手机时间未采集，不据此计算精确网络恢复耗时，也不能证明这次覆盖了所有静默半开情形。
5. 本机监督脚本独立审查通过，并用ssh -G确认空用户配置、唯一loopback转发、固定端口22、指定私钥/known_hosts、禁用agent、无代理或额外remote转发。08:33:44定向终止监督程序拥有的SSH子进程200388，08:33:49自动启动182216，08:33:50真实ADB命令恢复。
6. 旧电脑15555隧道已停止，ADB旧设备定向断开；未停止ADB server。当前监督PID217472，日志D:/geely/output/adb-relay-20261028/workstation-supervisor；这些PID仅是本次现场快照，后续须实时核验。

上述只证明 Termius 独立性和服务端主动断开后的恢复，不等于所有网络故障已解决。

## 待完成与已知限制

- 蜂窝数据关闭/恢复现场测试已通过；无响应旧监听长期残留、连续多次失败仍需专门测试。
- 原版 ConnectBot 源码存在转发申请失败仍显示连接、重连竞态及连续失败停止重试的风险。尚未修改该第三方代码，不能保证端口残留或连续失败后的恢复。
- 原版没有开机自动恢复主机连接逻辑。车机重启后需打开 ConnectBot 并点一次保存的主机；不得把 START_STICKY 或“保持连接”当作开机恢复已验收。
- 其“keepalive”偏好实际是保持屏幕唤醒，非SSH心跳；本方案没有靠该选项宣称探活。
- 本机监督脚本 scripts/run-geely-car-adb-tunnel.ps1 已审查、接管及实际退出重连验证；没有配置Windows开机启动，电脑重启后需要重新运行。
- B 音乐改善由用户确认；画面仍卡。urgentVideo 已保存true但性能对照尚未完成。本次转发修复不等于 CarPlay 整体性能验收通过。

## 使用与回滚

手机热点开启，车机连上热点；调试时保持车机 ConnectBot 的保存主机连接，手机不需要 Termius。电脑需有已授权私钥及独立核验的 known_hosts，运行数据隧道后连接 ADB 127.0.0.1:15556。

本机监督脚本需要 PowerShell7，显式传 RemoteHost、RemoteUser、PrivateKeyFile、HostKeyFile、LogDirectory；不创建开机任务。LogDirectory/stop.request 是停止入口，只终止该监督进程拥有的SSH子进程。启动不会接管已有监听端口。

本机当前调用（私钥内容不出现在参数中；不要把文件提交到仓库）：

```powershell
pwsh -NoProfile -File D:/geely/DiPlay-Geely-Android43/scripts/run-geely-car-adb-tunnel.ps1 `
  -RemoteHost 47.101.36.107 -RemoteUser carrelay_8600 `
  -PrivateKeyFile C:/Users/meicet/.codex/tmp/geely-adb-relay-20261027/workstation_ed25519 `
  -HostKeyFile C:/Users/meicet/.codex/tmp/geely-car-relay-20261028/known_hosts `
  -LogDirectory D:/geely/output/adb-relay-20261028/workstation-supervisor
D:/AndroidSDK/platform-tools/adb.exe connect 127.0.0.1:15556
```

当前仍在运行，不重复启动。保留上述任务专用密钥目录；若目录清理，须重新配置授权，不能跳过主机身份验证。车机保存主机当前显示名称为carrelay_8600@47.101.36.107Geely-ADB；实际hostname为47.101.36.107、port22，显示名称中的后缀不参与目标解析。

撤销车机通道：在 ConnectBot 主机列表长按该主机，断开连接；不会改变 DiPlay 或 adbd。服务器撤销授权需经既定管理通道将**专用账号**到期、终止仅该UID会话，再核验15556监听消失；仅设置账号到期不会终止已有会话。旧账号15555仅为临时回退，受2026-10-29到期限制。

现场证据及一次性脚本：D:/geely/output/adb-relay-20261028。私钥仅在受限本机临时目录与ConnectBot内部存储，不写入文档、命令日志或仓库。

## 晚间续测与密钥加载修正（19:17–19:21）

- 用户重新开启Termius，电脑重新建立旧15555数据隧道后真实ADB成功，车机uptime约350秒，仍为API18/C3ALFUS/H53、USB3-B。没有续用早上的进程状态作为当前事实。
- 用户截图显示SSH握手及服务器身份校验完成，但使用“内存中的任何公钥”认证未通过。车机UI实时证实：密钥car_relay_20261028.pem仍存在但未加载，“启动时载入密钥”未勾选；主机仍选择“使用任何已解锁密钥”。因此早上记录“已明确指定密钥”的结论不成立，早上成功依赖当时手工加载的内存密钥。
- 已通过正常UI开启“启动时载入密钥”，回读勾选为true；“使用前确认”保持false。主机改为明确选择car_relay_20261028.pem，回读实际字段后保存。
- 发现该固件某些PopupMenu的uiautomator坐标与实际截图不一致：按XML坐标点击未选中密钥，而是触发背后页面。改用实时截图定位，随后确认字段显示密钥文件名，避免将点击动作当作保存成功。
- 仅force-stop并重新启动org.connectbot；未清数据、未重启车机或DiPlay。点保存主机后，19:21:04新15556返回CONNECTBOT_AFTER_PROCESS_RESTART_OK及车机时间/uptime。此次验证覆盖应用进程重启后的密钥加载及认证，不代表开机自动连接。
- Termius15555暂作本轮备用，当前主要采样再次使用15556。已请求用户进入CarPlay播放缓存音乐并滑动列表；19:21初次拉取的DiPlay日志末尾仍为08:38历史记录，不能作为晚间播放证据。

## 来源链接

- [ConnectBot v1.9.13 官方发行](https://github.com/connectbot/connectbot/releases/tag/v1.9.13)，源码基线4b32fe6ae05ba9ec5511e5f7d43cb2177aeb2d02。
- [Apple 平台网络与后台说明](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/NetworkingOverview/Platform-SpecificNetworkingTechnologies/Platform-SpecificNetworkingTechnologies.html)。
- [OpenSSH7.4配置解析源码](https://github.com/openssh/openssh-portable/blob/V_7_4_P1/servconf.c)：Match内使用KbdInteractiveAuthentication，不使用仅全局允许的ChallengeResponseAuthentication。
