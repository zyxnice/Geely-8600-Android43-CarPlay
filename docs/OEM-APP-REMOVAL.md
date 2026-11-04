# 原车高德与会员中心移除后的服务调用

现场记录来自星越 2019 款 8600、C3ALFUS、Android 4.3（API18）、固件 `06.03.08600.H53.00060`。

## 讯飞导航服务

[下载实车验证版独立桥接 APK（1.0）](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/releases/download/navigation-bridge-v1.0-20261103/Geely-NavigationBridge-1.0-20261103-66f37f2d008a.apk)

卸载原车高德后，HUD 闪烁消失，但原厂讯飞语音客户端 `com.iflytek.cutefly.speechclient` 仍尝试绑定 `com.iflytek.navigationservice.NavigationService`。高德已移除，绑定失败后约每 2 秒重试。安装前的 30 秒现场日志中记录了 14 次 `NavigationService not found`。

解决方案是独立安装 `com.geely.navigationbridge`。在上述固件且原车高德已移除的情况下，它接受讯飞的服务绑定与回调注册，由讯飞自动拉起。导航请求返回不可用，位置返回空以保留讯飞原有的平台定位后备。手机高德和 CarPlay 的路线转发不在该服务的功能范围内。

从归档提交 `6c002917111044f852a012dc4f0ec96bf9a1235b` 构建。在该提交的仓库根目录运行 `./gradlew :navigation-bridge:testDebugUnitTest :navigation-bridge:assembleDebug`，产物在 `navigation-bridge/build/outputs/apk/debug/`。API18 安装需要 APK v1 签名。 [独立桥接源码](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/tree/6c002917111044f852a012dc4f0ec96bf9a1235b/navigation-bridge)

2026-11-03 实车已安装版本为 `1.0`，回读 APK 的 SHA-256 为 `66f37f2d008a414688362df8a921796058f89583d04f7085783d5995a22efc1d`。重启后确认讯飞以 `BIND_AUTO_CREATE` 直接绑定独立服务，连续 65 秒采样未再出现缺服务重试，绑定保持稳定。DiPlay 完全退出的单独对照、反复断电和语音电话、音乐、车控回归仍待实测。

回滚时卸载 `com.geely.navigationbridge`。恢复原车高德前先卸载兼容包，避免两个服务同时响应同一绑定请求。

## 会员中心

在线移除原车会员中心 `ecarx.membercenter` 后，Android 的 ActivityManager 仍保留其常驻服务的重启记录，继续尝试拉起并报 `Unable to get package info`。反复拉起和失败处理会增加后台 CPU 工作，但现场没有同条件对照，无法量化它对整车 CPU 占用的影响。现场执行普通 `force-stop` 后，重启记录仍在。

2026-11-02 停车重启后，现场确认会员中心仍处于移除状态，进程列表和启动事件采样均未见该包启动。该次开机早期三次采样的整机 User+System 分别为 27%、23%、21%。采样时未确认 CarPlay 音视频负载，无法用这些数字计算卸载或重启带来的 CPU 降幅。
