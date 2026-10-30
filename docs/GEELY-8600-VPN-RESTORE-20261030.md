# 8600 VPN 开机授权恢复候选

日期：2026-10-30。状态：本地实现、测试和打包完成；未装车、未实车冷启动验收、未推送或发布。

## 行为

- 仅 API18 / C3ALFUS / `06.03.08600.H53.00060` / 固定 geely8600 包，且 geelyUsb manifest 明确 opt-in 生效。
- 首次未授权正常显示系统“我信任此应用”窗口。系统 prepare 确认已授权后记录 boot_id；首次升级后仍可能需要确认一次。
- 下次内核启动时尝试一次原厂助手恢复，随后以系统 prepare 复核。应用重开或同次启动撤销不自动恢复；系统对话框拒绝会清除恢复信任。
- 不同应用占用 VPN 时不抢 owner；已经运行的系统内建 VPN 被拒绝。不能与系统内建 VPN 同时启动，原厂没有联合原子查询/修改接口。
- 恢复失败/超时后回读实时系统授权：没有授权则显示系统确认；已授权则正常继续。已经发出的 Binder 不能撤销，不能把租约取消写成绝对阻止远端变更。
- 平台不符保持正常授权流程，不改固件、系统文件、USB或媒体路径。

## 两个基线

用户要求各自保留 output1 与 output3 的媒体实现。output1 取 aaec231（代码与归档 bc13d8f 相同，之后仅文档变化）；output3 取 output4 开始前快照及未被 output4 修改的既有文件，六项 output3 历史源码 SHA-256 全部匹配。两个候选之间仅保留原有八个媒体文件差异；VPN代码相同，没有混入 output4 统计或模拟代码。

原工作区含未提交媒体诊断，保持原样；临时 Git index 构造两个树、独立导出源码构建，未切换原工作区或暂存用户文件。原生工作树入口不识别 chat 根目录 D:/geely 下的嵌套仓库，因此本任务不创建任何新 worktree。

本地分支：`fix/geely-vpn-output1-20261030`、`fix/geely-vpn-output3-20261030`。产物目录：`D:/geely/output/vpn-restore-20261030`。

| 候选 | SHA-256 | 本地 shared/common 回归 |
| --- | --- | --- |
| DiPlay-8600-USB3-B-output1-vpn-fix.apk | 17f97ccc77b9815a7eb4d6b02fe5fda4b3fbce33d719ea7e0bcfc873f82623f6 | 749项，746通过、3项既有host-JNI跳过，零失败 |
| DiPlay-8600-USB3-B-output3-vpn-fix.apk | 779329b0df129ceca5788976138c17deede3117198e0209f20b9adfd59e6314c | 761项，758通过、3项既有host-JNI跳过，零失败 |

两包保留相同 package / versionCode29 / versionName `0.2.10-H53-USB3-B`，需通过文件名和 SHA 区分。API18 v1/v2签名、ARMv7、DEX035 helper、B原生库、两项授权认证资产哈希及同各自基线同签覆盖身份校验通过。

## 验证与剩余边界

新增10项策略和3项租约测试覆盖新旧boot、首次/拒绝、其他应用VPN、已有内建VPN、竞争owner变化、系统复核、已打开FD被截断、过期和nonce不符。独立agent实际diff复审后未发现阻塞本地候选交付的问题。

通用 Android4.3.1 ARM 模拟器执行独立 probe DEX，实际调用生产 helper 的 restoreAsSystem：UID1000恢复成功、重复调用already_prepared、过期拒绝、租约取消拒绝，均按预期。probe 不打入APK。此验证不具备原厂 OEM 服务，不能替代实车全链路验收。具体授权与取消边界见 ADR-0007。

独立agent在同一本地模拟器逐包覆盖安装，从正常DiPlayActivity点击USB：两包首次均显示原生VPN确认，取消后显示拒绝，再次进入仍正常确认；检查窗口未发现启动崩溃，平台不匹配时未进入恢复分支。同签覆盖通过。证据在产物目录 `emulator-ui/verification.md`。本次启动的模拟器验证后关闭，未触碰主机VPN或车机。

## 装车与回退

等用户通知能连车时，先核对板型/固件、已安装APK路径与SHA，识别实际output1/output3，再选对应候选同签覆盖，不卸载、不清数据。遇到未知APK哈希保留未知状态，不凭相同versionName推断基线。当前阶段仅本地准备，未执行装车。

首次确认后须完成真正车机重启、再次进入USB CarPlay，确认不再询问VPN信任，连接/音乐/画面正常；重开应用及拒绝授权也要验证。软件进程重启不能代替内核冷启动。回退同签覆盖原基线包，不删除配置或应用数据。

原output1 SHA：131bbff7e3f0ebaf4af994b70e93e4933d52da4b1fb3773edaf485718eb0c45c；原output3 SHA：579be0293691825d7d4e8d667b7da46fed5778265c3d6230394f984b1990ab2d。原始APK不变。
