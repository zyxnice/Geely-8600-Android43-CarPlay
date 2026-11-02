# 原厂 HUD 顺序核验与图标协议修订

日期：2026-11-02；阶段：构建及复核通过，连接恢复后已覆盖安装，正常会话复核中。

## 本次范围与依据

用户授权参考原厂顺序执行新方案，随后明确已停稳可以安装。参照原厂高德逆向交付factory-amap-reverse-20261102中的AutoNaviReceiver、AutoNaviRpc及GuideInfoProtocolModel，当前Policy本来已按开始10019=8、引导10001、结束10019=9执行，无证据需要重写生命周期或添加等待；保留默认ON、手动OFF、忽略原车状态、持续发送、租约、代次和5秒过期清除。

Decision: code-change。已证实的独立协议错误：现有五类目的地/途经点动作生成ICON15，但原厂turning-direction处理器对13..16不发送方向更新。最小改动归GeelyNavigationFrame所有，改为ICON1，经原厂编码写turning0清除旧箭头；距离/路名继续更新，不伪造EXTRA_STATE39或路线结束。没有改变原厂接收服务、单位编码、道路分包、原始MCU/CAN、USB/VPN/媒体或音频。

TDD Route: off（未要求严格TDD）；新增实际契约回归并运行相关完整测试。独立复核已检查producer及原厂smali消费契约；生产Policy与已装repeat基线逐字节相同。来源竞争和物理HUD闪烁仍是独立待验项，不能被该图标修订关闭。

## 单来源对照尝试

前查确认已装APK SHA85e4c99554be5b285637356c59f183f089c29e80133cb1eb029cbdf371d85001，高德UID10041、versionCode554、user0 enabled0。原车语音客户端绑定其NavigationService，后台及定位服务常驻。

仅尝试pm disable-user --user 0 com.autonavi.amapauto一次。命令退出0/stdout空，后查仍enabled0且主进程3733、定位进程4239在；未确认停用，不构成单来源对照。未保留该命令stderr，不能据此断言具体拒绝原因。未扩大到force-stop、清数据、卸载或其它特权路径。接收服务extraservice和高德原件保持。

预备功能恢复入口pm enable --user 0 com.autonavi.amapauto；如日后使用会设为显式enabled1，并非原始默认0元数据。当前状态未变，无需发送恢复操作。

## 制品与验证

从精确已装repeat源码增量构建，仅GeelyNavigationFrame及Frame/Policy测试三项差异。没有将路名、距离、时间、方向改为直接透传调用；单个10001仍交给原厂setHud_NL，沿用其内部字段编码与调用顺序。

geelyUsb/debug构建、三项制品校验通过；813项测试中810通过、3项既有跳过，0失败/错误。独立复核核对原厂smali与实际增量，无阻断。候选DiPlay-8600-USB3-B-output3-vpn-usb-r2-hud-oem-sequence.apk，11564737字节；SHA256：24d7bb5197be52b17583a46476e04236b8ddf77e276592e585d67b8817846b16。源注释精准修订后重新构建及校验，候选SHA不变。

分块还原方案本地逐字节验证一致，待传输5120527字节。首次上传在远端mkdir前报告device offline，没有开始安装；离线前最后实机核对仍为85e4c995完整回退基线。连接恢复后须重新核验基线/boot再传输和安装，不能把构建完成写为装车完成。

## 连接恢复与实际安装

用户通知ConnectBot已打开后，本机ADB重新显示device。重新核对C3ALFUS/H53.00060及当前实际APK，仍为85e4c995完整回退基线；新的boot_id为e632340b-4726-4ea7-a985-9ada6815983d，安装脚本guard据实时值更新，未跳过启动身份检查。

传输5120527字节后压缩载荷SHA通过；车机完整还原11564737字节APK，SHA24d7bb51完整值与候选一致。旧基线备份/data/local/tmp/DiPlay-hud-repeat-before-oem-sequence-20261102.apk并校验后，pm install -r返回Success。

实际安装/data/app/com.shihab.diplay.repair1.geely8600-2.apk，SHA24d7bb5197be52b17583a46476e04236b8ddf77e276592e585d67b8817846b16；UID10062、versionCode29、versionName0.2.10-H53-USB3-B保留。没有清数据、卸载、重启、关机或再次操作原车高德。正常CarPlayHostActivity入口已启动。

物理方向图案、行驶闪烁、导航声音均未据本次代码/构建判为通过。

## 用户再次手动重启后的只读核验

boot_id变为8b6d00af-c91b-4f72-a397-dc34dcacd459；COMMOND_Server查询found，角色0/充电sdp，iPhone已枚举且bConfigurationValue=6。实际安装APK仍为24d7bb51完整SHA，无需重复安装。

PID5179正常入口收到导航，08:37:37.120发送begin；08:37:42.550记录end reason=no-fresh-guidance，08:37:44.860重新begin。当前只读JDI为initialized=true、requestedEnabled=true、currentStatus=STALE、有效owner；说明本次导航输出已进入后又被数据新鲜度条件清除，不是手动关闭开关。临时18705转发已finally删除。

此处直接证实本应用发生过期清除/重新开始，与此前USB准备失败分开记录。它可能影响显示连续性，但没有同时录制物理HUD，不能宣布它解释全部行驶闪烁。下一步应核对手机距离字段更新间隔、路线活跃/结束证据与5秒截止规则，本次只读核验未更改该规则。
