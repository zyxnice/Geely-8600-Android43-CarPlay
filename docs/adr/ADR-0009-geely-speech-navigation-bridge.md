# ADR-0009：讯飞导航兼容服务与手机导航转发边界

- 状态：Accepted（独立兼容端；手机转发仍未实现）
- 日期：2026-11-03
- 作者：Codex
- 最后修改：2026-11-03

## 上下文

用户要求：CarPlay未连接时，由兼容NavigationService接受讯飞绑定以停止每2秒重连；连接后，沿用讯飞一次语音输入自动交给iPhone高德，必须支持锁屏、后台及连续使用。允许手机端辅助配置，不接受重新向Siri说目的地。

后续用户明确缩小本轮范围：先拆成独立小APK，解决讯飞持续重连，不依赖DiPlay启动。用户不在车上，当前连接离线；本轮完成构建与离线检查，安装和实车验收待恢复连接。

H53/API18原厂语音在初始化时绑定action `com.iflytek.navigationservice.NavigationService`。实际客户端rp/rq的descriptor为`com.iflytek.navi.aidl.NaviAidlService`，transaction 1注册回调，2—13为搜索/导航等布尔接口，14返回位置字符串。高德包中较新Stub的getLocation为16，不能照搬其ABI。连接成功后dx置connected=true，dy循环退出；业务请求是否成功是另一层返回值。

车机现有CarPlay接口有requestSiri、手机到车机的路线信息。端点目录的destinationSharing更新方向是手机到车机，不能据此推定支持发送目的地。原厂libiPodForiAP2的doRequestAppLaunch发送0xEA02、参数0字符串和参数1字节，当前证据不能确定字符串可为高德导航URL，也未验证锁屏执行。

高德官方iOS导航URL支持按经纬度导航；必须由手机端执行。Apple UIApplication.open文档说明URL交给目标应用并将其带到前台；普通应用后台时间有限。以上不能证明所有辅助方案不可能，也不能证明快捷指令/自制App能锁屏长期接收且连续拉起高德。尚无已核验的手机执行通道。

## 决策

独立模块navigation-bridge、包名com.geely.navigationbridge，使用仓库Gradle wrapper及既有Android插件，min/target SDK18，启用v1签名。沿原common Service/日志与Robolectric测试风格组织，没有任何DiPlay模块依赖。移除此前common候选与mobile/geelyUsb中的禁用声明，仅新APK注册此服务。

安装后由讯飞BIND_AUTO_CREATE启动独立进程。需要激活新包时仅显式startservice一次，onStartCommand立即stopSelf并返回START_NOT_STICKY，不引入开机接收器、轮询或常驻started任务。是否停止实车重连仍需安装后观察，不以架构决策已接受代替验收。

候选在H53/API18且高德未安装时接受绑定；每笔Binder事务核对调用UID所属的讯飞包。接口1仅对非空回调返回成功，2—13返回false，14返回空字符串，保留调用者既有位置后备路径。不保留回调、不创建轮询、不触发CarPlay连接、不发送车辆广播。不把“兼容服务已连接”写成“手机已开始导航”。

完整转发分支尚未实现。后续必须先取得并验证手机在锁屏和后台的可靠触发方式，以及目的地解析/选择、坐标体系、有效期、去重、超时、实际执行回执。语音搜索有时依赖地图返回POI候选，不能只实现navigate转发或伪造搜索结果。多候选时不得擅自选第一个。

## 后果

正面：不改原厂讯飞APK/签名；接口可离线校验；不承诺未发生的导航，不影响已装车版本。

负面：新增一个独立绑定进程，资源收益需实测；目前未安装，因此实车重连问题未闭环，也未实现手机导航。装车需核验绑定退出、语音错误提示、位置后备及既有语音功能。恢复高德前必须卸载兼容APK，避免两个相同action的服务竞争，onBind拒绝不替代解析层互斥。

## 替代方案

- 修改原厂讯飞重试代码：可直接处理缺服务循环，但需重签/ODEX及原厂功能验证，且不提供手机转发。
- 恢复原厂高德：可恢复原绑定对象，但不满足卸载减负与手机高德接管目标。
- 改用Siri：现有代码有调用能力，用户明确不接受再次说目的地。
- 快捷指令轮询或普通后台辅助App：可用于另行授权的能力验证，但未证明锁屏后台连续执行，不作为已成立实现。

## 撤销条件

用户不再需要兼容服务或恢复原高德时卸载独立APK；无需修改DiPlay或系统分区。手机转发能力不再是本轮兼容端装车的前置条件，但未来手机导航交付仍需原有锁屏/后台硬验收。

## 证据与验证

- 固件逆向：工作区output/service-conflicts-20261103/amap-callers.md及speech-analysis；原厂调用发送结构见analysis/usb8600/native/ipod-iap2-decompiled.txt:19031。
- [高德iOS导航URL](https://lbs.amap.com/api/amap-mobile/guide/ios/navi)
- [Apple打开URL](https://developer.apple.com/documentation/uikit/uiapplication/open(_:options:completionhandler:))
- [Apple有限后台执行](https://developer.apple.com/documentation/uikit/extending-your-app-s-background-execution-time)
- 已执行独立模块GeelyNavigationServiceTest的四项测试：14个事务布局/返回语义、未授权UID拒绝、错误action/非目标平台拒绝，以及模拟目标身份下无DiPlay绑定/原高德存在拒绝。API28框架模拟身份，不替代API18真实绑定和原厂语音验收。
- 独立APK构建和独立复核通过，apksigner以min-sdk18验证v1/v2签名通过；无需CarPlay认证资产。未安装，真实运行及冷启动验收待完成。

## 变更历史

| 日期 | 变更类型 | 原因 | 操作人 |
|---|---|---|---|
| 2026-11-03 | 新增Proposed | 记录兼容端候选与手机端能力缺口，禁止半完成误报 | Codex |
| 2026-11-03 | 接受独立兼容端决策 | 用户缩小本轮目标，拆成独立APK并移除DiPlay内候选 | Codex |
