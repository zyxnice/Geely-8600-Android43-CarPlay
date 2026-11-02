# HUD 默认直接输出与导航字段去重

日期：2026-11-02；当前阶段：已覆盖安装，正常入口恢复视频与HUD发送，物理显示待验。

## 范围

用户明确要求CarPlay独占导航，不检查原车导航状态；默认发送有效导航，只在实际HUD内容变化时再次发送。本次不卸载或停止原车高德，不改变USB、VPN、媒体基线或原始车辆传输。

删除分类器、外源监听、UNKNOWN等待和确认入口。保留默认ON/手动OFF持久化、接收器APK/ODEX身份、单写者租约、通道代次、5秒距离过期及有限清除重试。比较candidateFields实际OEM载荷，排除distanceUpdatedNanos；成功发送才缓存，清除或撤销后清空。

## 已核验现场事实

- 本日已安装默认开启版，pm install -r返回Success，实际文件SHA为df2da88de1ef884b28a554c0a604037a663627d75285c942e8a522b12652ffaf，UID10062；未清数据、卸载或重启。
- 只读JDI读取requestedEnabled=true、NAVIGATION_STATE_UNKNOWN、有效owner，说明该版本被UNKNOWN等待阻挡；没有调用应用方法、修改运行字段或挂起进程。
- 手机路线更新约1Hz；原厂Extra接收器同时处理方向0、距离0等空导航字段。不能仅据此确认行驶闪烁根因。

## 验证边界

独立审查已检查直接输出删除与实际载荷去重增量，无阻断项。测试新增时间刷新不重发、5秒过期、清除后同内容重发、每个可变OEM字段及可选值null/0变化。

geelyUsb及debug构建成功；811项测试中808通过、3项既有跳过，0失败/错误。HUD Policy 14项全部通过；USB/API18/DEX035/签名/认证资产及导航metadata隔离的3项制品校验通过。未将已有shared lint 15 errors / 71 warnings记为通过。

最终候选：DiPlay-8600-USB3-B-output3-vpn-usb-r2-hud-direct.apk；SHA256：e404fd582f432665d3d7592fc604c133bda65b0df5277685d43ac58627294952。构建源码与已装默认开启版逐文件比对，仅10项HUD文件修改及2项旧状态源码/测试删除。未混入原始脏工作区的视频实验。

## 直接输出版装车

分块传输5314139字节，完整APK还原16610728字节，SHA一致。安装前核对C3ALFUS/H53.00060、boot_id db4b3c49-f813-442e-8223-91647fe431ef及已装基线。旧包备份/data/local/tmp/DiPlay-hud-default-before-direct-20261102.apk并校验df2da88d完整SHA后，pm install -r返回Success。

实际安装/data/app/com.shihab.diplay.repair1.geely8600-2.apk SHA与e404fd58完整候选一致，UID10062、versionCode29/versionName0.2.10-H53-USB3-B保留。没有清数据、卸载、重启或关机。

正常CarPlayHostActivity入口启动PID15160；07:56:06视频解码首帧渲染，07:56:08输出导航begin及有效候选。07:56:28至07:56:33路线帧计数39→44、distanceUpdatedNanos更新，实际候选字段相同。两次广播历史快照中DiPlay记录均为07:56:08的两个广播（开始及首次导航），未观察到这段相同内容期间重复写入；这是一段有限运行样本，不能替代所有路线变化场景验收。

独立agent从本机只读ADB/JDI复核同一正常会话：initialized=true、requestedEnabled=true、currentStatus=SENT、ownerEnabled=true、hasOwner=true；没有挂起、调用或修改应用字段。稳定导航下广播历史至07:57:10仍仅07:56:08两条自有发送，支持本段去重已生效。临时18706转发已finally删除并核对无残留。

原车高德仍在，可能覆盖共享接收端；不再有来源避让保证。物理HUD持续显示、行驶闪烁及异常残留尚未验收。
