# HUD 后台高德误拦截修订

## 目标、边界与初始状态

用户授权修订“后台常驻即导航占用”判断，并直接覆盖安装。原车高德保留，不卸载、不清数据，不改媒体/USB/VPN，不自动开启HUD试验或发送试验导航。交付须核对实装文件，HUD物理显示单独验收。

实时核对已装trial1：`/data/app/com.shihab.diplay.repair1.geely8600-1.apk`，SHA256 `610944976b7ad687fe935174e438f0c893b79b19770c6f2ec3759209d192e7c9`，boot ID `63c29cfe-1f00-431f-86cc-b5e37a692e00`。

## 证据与决策

用户未打开原车导航；现场服务记录显示原车语音绑定NavigationService，AutoBackgroundService自动启动，故进程存在不是正在引导的证据。手机侧已收到可用路线和maneuver，原厂APK与ODEX匹配固定校验值。原试验版进程判断会阻断；仅收到候选不证明已发送或物理显示。

Decision: code-change，TDD Route: off（用户未要求严格TDD，使用既有JUnit回归）。移除进程存在即拒绝，按10019的8/10开始和9/12结束维护已知状态；10001的有效数值及路线证据用于检测注册前已启动的路线，不限制为本地可编码图标或非空路名，0米/秒有效。空字段保持UNKNOWN，不清除ACTIVE；已知ACTIVE不靠超时释放。未同意时不把控制器提前污染为failed。外源END清共享接收端时，仅重置仍拥有的本地输出以便下一新鲜帧重新begin，不授权、不解除既有失败。

固件枚举 `com/ecarx/sdk/navi/entitys/NaviStateType.smali` 命名和AutoNaviReceiver/Rpc实现支持8/9/10/12语义。没有已核验的跨进程状态getter；初始UNKNOWN仅在同次人工确认原车未导航的停车试验范围内允许尝试，非自动确认空闲。广播监听与发送非原子，10001判据尚非完整高德协议。

读取现场字段的有界独立Java探针在app_process启动后被Killed，未取得字段；已停止尝试，未发送导航广播。新包记录低频状态转换及决定性字段用于继续验证。

## 验证与安装

构建脚本：`D:\geely\analysis\build_geely_hud_source_state_20261101.py`。隔离源码从精确已装trial1复制，仅九项HUD相关文件不同，逐文件字节比较。原工作区已有USB、VPN、视频等未提交改动保留，未混入候选；专用分支保留，未提交/推送。

独立agent复核实际diff及固件语义，进程误判、UNKNOWN边界、接管不补END、外源END重建均已复核。最终817项测试：814通过、3项既有host JNI跳过、失败为零；geelyUsb及普通debug构建、API18/ARMv7/DEX035/v1签名/认证资产/同签覆盖与metadata隔离检查通过。`git diff --check`通过。没有重新声称全量lint通过；此前shared全量lint的既有15 errors / 71 warnings未纳入本轮修复。

最终APK：`D:\geely\output\hud-source-state-20261101\DiPlay-8600-USB3-B-output3-vpn-usb-r2-hud-trial2.apk`，SHA256 `67ab78082c032e6640e532e7c6436df3f942fc74ff2618784f7da4d95266482d`，字节数16588520。构建回执、三份verification日志及精确源码副本位于同目录。

2026-11-01约11:02：实车重建整包SHA一致；安装前将trial1备份为 `/data/local/tmp/DiPlay-hud-trial1-before-state-20261101.apk` 并核对原SHA。`pm install -r`返回`Success`。重新读取真实安装路径 `/data/app/com.shihab.diplay.repair1.geely8600-2.apk`，SHA与最终候选完全一致；UID仍10062，versionCode29 / versionName `0.2.10-H53-USB3-B`。同签覆盖，没有卸载/清数据。已从正常Activity入口打开CarPlayHostActivity，PID28862启动。未重启或关闭车机；HUD试验未自动开启，未替用户发送HUD消息。

安装核验完成，物理HUD显示及当前后台广播是否仍误判须由新包日志和停车人工试验继续验收，不能由本地测试或安装成功替代。

启动后首轮USB NCM bulk OUT就绪等待超过15000ms，既有重连流程自动重建；随后日志出现PID28862的 `video decoder first input`，确认已恢复收到CarPlay视频。没有因此追加USB代码修改、关机/重启或要求用户拔插。HUD试验仍未自动开启，尚无本次HUD物理显示通过证据。

后续用户反馈“可以了”，并授权提交推送。记录为本候选现场用户确认，不扩展为多次冷启动、流畅度、所有图标和异常清除全场景验收。发布源码使用精确实装构建副本；另一个视频优化分支的未提交改动保留，不混入本次推送。
