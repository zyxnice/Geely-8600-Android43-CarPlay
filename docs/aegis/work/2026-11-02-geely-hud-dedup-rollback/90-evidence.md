# 撤销 HUD 导航内容去重

> 历史记录（2026-11-03分类核对）：下文保留该次试验／计划的事实与限制；“当前、最新、待安装、下一步”仅指记录当时，不代表今天的安装或验收状态。请先读[最新状态](../../../GEELY-8600-STATUS.md)；本记录不授权继续执行旧现场操作。

日期：2026-11-02；状态：已回退安装，正常会话持续发送已复核。

用户反馈刚新增的内容去重无效并要求回滚。仅撤销payload字段去重，恢复GeelyNavigationFrame整帧比较（包含distanceUpdatedNanos）；默认开启、手动OFF持久化、忽略原车导航状态、身份核验、租约、代次与5秒过期清除保留，不恢复UNKNOWN等待，不改USB/VPN/媒体，不卸载原车高德。

从已装e404fd582f432665d3d7592fc604c133bda65b0df5277685d43ac58627294952候选精确源码构建，仅Policy及其测试两项变化。独立复核已确认回退范围；新增回归预期连续10次新鲜时间更新会连同首次共发送11次导航，过期清除及重新开始仍有效。

构建geelyUsb/debug及3项制品校验通过；811项测试中808通过、3项既有跳过，0失败/错误。候选DiPlay-8600-USB3-B-output3-vpn-usb-r2-hud-repeat.apk，11564539字节，SHA256：85e4c99554be5b285637356c59f183f089c29e80133cb1eb029cbdf371d85001。

分块传输5119569字节，完整APK还原后SHA一致。安装前核对固定平台、boot_id与当前已装e404fd58完整SHA；备份/data/local/tmp/DiPlay-hud-dedup-before-rollback-20261102.apk并校验后，pm install -r返回Success。

实际安装/data/app/com.shihab.diplay.repair1.geely8600-1.apk SHA与候选一致，UID10062及versionCode29/versionName0.2.10-H53-USB3-B保留。未清数据、卸载原车高德、重启或关机。已从正常CarPlayHostActivity入口启动复核。

独立本机只读ADB/JDI复核PID26629：currentStatus=SENT、租约有效；视频渲染计数增长。相同导航字段保持时，距离更新时间持续刷新，08:07:00至08:07:10每秒均有该应用导航广播，确认持续发送恢复。临时调试转发已移除，独立复核未操作界面或导航。

不把软件回退等同于物理HUD或闪烁已修复。
