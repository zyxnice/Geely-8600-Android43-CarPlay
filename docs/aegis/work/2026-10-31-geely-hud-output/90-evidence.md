# 8600 HUD 受控试验交付状态

## 2026-11-01 最新实车更新（取代下方离线状态）

### 后续手动重启与 USB 恢复

用户确认停车并授权重启。正常 `ShutdownActivity` 重启入口被系统以缺少 `android.permission.SHUTDOWN` 拒绝，没有执行远程重启。用户随后手动完成重启；实时 boot ID 变为 `63c29cfe-1f00-431f-86cc-b5e37a692e00`（首次读取 uptime 约 80 秒），确认与此前不同，`COMMOND_Server` 恢复 found。

打开既有 CarPlayHostActivity 后日志确认 `runtime preparation result=prepared`，发现 Apple `05ac:12a8`，configuration 2 转 6 成功，USBMUX/NCM/VPN、iAP2 READY 均通过；后续 AirPlay SETUP 成功，视频解码器配置为 1920x720，并收到首个视频输入。存在视频 backlog 超过 250ms 的恢复警告，不能称流畅性验收通过。用户实际画面、导航声音与触控体验仍待确认。本阶段没有修改或重新安装 APK，也没有额外触发 HUD 导航输出。

- 调试连接恢复，实时核对 C3ALFUS / API18 / 固件 `06.03.08600.H53.00060`，boot ID `7fc006d5-240c-4e60-ae49-5d0f3dbad8e6`。
- 已装 USB r2 的实际文件 SHA 为 `57da4a0fcf8c604797ce5ab38715c233582c5be0fc184da3136f752859bab1b6`；安装前备份至 `/data/local/tmp/DiPlay-usb-r2-before-hud-20261031.apk` 并核对一致。
- 分片传输、重建最终 APK 校验通过，`pm install -r` 返回 `Success`。重新读取实际安装路径 `/data/app/com.shihab.diplay.repair1.geely8600-1.apk`，SHA 为 `610944976b7ad687fe935174e438f0c893b79b19770c6f2ec3759209d192e7c9`，UID 10062、versionCode 29、versionName `0.2.10-H53-USB3-B`。同签覆盖，没有卸载或清数据。
- 原厂 `COMMOND_Server` 当前查询未找到；命令桥只读 id 探针没有生成输出文件，未认定桥恢复。
- 用户随后明确取消关机：“不需要关机了，我刚刚都关过了”。未发送系统关机请求，停止关机准备。用户报告已关过机，不据此断言当前物理电源状态。
- HUD 输出保持默认关闭，本轮未发送导航广播；实际导航数据与 HUD 显示尚未验收。

日期：2026-10-31。分支：`codex/geely8600-hud-implementation-20261031`。未提交、未推送。

## 目标与授权

沿历史 HUD 方案实现原厂广播受控出口，保留已装 USB 两秒恢复 r2、VPN 和 output3 媒体基线。用户授权覆盖安装，安装核验成功后正常关闭车机。本轮没有授权无人值守发送导航试验消息。

## 本地证据

- APK：`D:\geely\output\hud-trial-20261031\DiPlay-8600-USB3-B-output3-vpn-usb-r2-hud-trial1.apk`。
- SHA256：`610944976b7ad687fe935174e438f0c893b79b19770c6f2ec3759209d192e7c9`，最终文件已重新读取核对。
- 精确构建基线为已装 r2；构建副本、24 项覆盖文件与九项保留文件记录于同目录 `receipt.json`。
- shared/common 共 806 项测试：803 通过，3 项既有 host JNI 跳过，失败为零。geelyUsb、普通 debug 构建成功。
- 三份 verification 日志记录 API18、ARMv7、DEX035、v1 签名、同签覆盖身份、认证资产、Trial 类及普通变体 metadata 隔离检查通过。
- 独立 agent 复核实际 diff，已修复连接代次、日志、UI 同步及停用竞态；最终局部 API18 lint 抑制复核无新增阻塞。
- `git diff --check` 通过。shared 全量 lint 仍有 15 errors / 71 warnings；最终新 HUD 文件无命中，不能称全量 lint 通过。日志：`D:\geely\output\hud-trial-lint-terminal-20261031.log`。

## 实车状态与阻塞

准备安装时 `127.0.0.1:15556` 离线。定向重连及重建本任务本机 SSH 子进程后仍未恢复；最后 reconnect 后设备列表为空。没有更改隧道服务器服务或扩大到其他设备。

**HUD APK 未上传安装，关机未执行。** 最后有证据的已装包仍是 USB r2，SHA256 `57da4a0fcf8c604797ce5ab38715c233582c5be0fc184da3136f752859bab1b6`。离线不证明车机已关机。

本地已准备分段上传、精确重建及正常 PowerManager.shutdown 助手；均未在车机执行。没有运行 HUD 输出广播或 raw MCU/CAN。

## 续接条件与顺序

恢复车机 ConnectBot 与热点调试连接后，先重新读取板型、固件、boot ID、pm path 和已装 SHA；上传并核验最终 APK，备份 r2，执行同签 `pm install -r`，重新读取实际安装文件 SHA 与 UID。仅安装核验成功后执行一次原厂正常系统关机，并区分关机请求被接受与物理断电证据。

HUD 默认关闭；实际 iPhone 导航元数据、HUD 图标/路名/距离、来源互斥及异常残留仍需停车人工试验。构建和测试不能替代实车验收。当前没有自动续跑机制。
