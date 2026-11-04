# 讯飞导航兼容服务（独立 APK）

仅适用于C3ALFUS/API18、固件06.03.08600.H53.00060且已移除原厂高德的车机。
没有DiPlay依赖、界面、网络/录音权限、开机广播或后台轮询。由讯飞的BIND_AUTO_CREATE绑定启动独立进程。
接受连接和有效回调注册；导航业务返回不可用，位置返回空以保留讯飞原有平台定位后备。
不提供CarPlay/iPhone高德转发，不假报导航成功。

构建：仓库Gradle wrapper执行 `:navigation-bridge:testDebugUnitTest :navigation-bridge:assembleDebug`。
产物：`build/outputs/apk/debug/navigation-bridge-debug.apk`；包名`com.geely.navigationbridge`，版本1.0/1。
API18需要v1签名，交付时用本机apksigner验证。测试用API28 Robolectric，不替代原厂API18实车验证。

安装前核对：固件、讯飞仍安装、高德已移除、同action无其他兼容服务，并保留安装前日志。
安装后若包处于stopped状态，可使用API18的 `am startservice -n com.geely.navigationbridge/.GeelyNavigationService` 激活一次；服务应立即stopSelf，不设常驻启动任务，由讯飞现有重试触发绑定。
安装和激活只操作此新包，不停用讯飞，不启动或停止DiPlay，不改系统分区。

验收：确认讯飞ServiceConnection连接到本包，回调注册成功；至少60秒日志不再出现NavigationService not found，进程无崩溃/反复重启。
还需实车验证DiPlay未打开时、下一次自然启动后均可绑定，以及语音唤醒/电话/音乐/车控未出现回归。
不能以进程存在、单次bind成功或CPU单个样本替代这些验收。

回滚只卸载`com.geely.navigationbridge`，原有缺包重试可能恢复；恢复原厂高德前先卸载兼容包，避免同action解析冲突。
当前车机离线且用户不在车上，未安装，重连问题尚未完成实车闭环。
