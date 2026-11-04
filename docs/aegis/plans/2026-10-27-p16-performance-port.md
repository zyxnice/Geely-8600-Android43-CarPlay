# 8600 USB2 基于 P16 的流畅度优化执行方案

状态：计划，未实施。用户本轮只要求执行步骤，且要求暂不采集车机。

## 目标、基线与范围

目标：保留已连通的 USB2 自动准备/重连能力，在同等分辨率、帧率和使用场景下改善画面与触摸延迟；音乐卡顿另用音频数据判定，不能由视频改善代替验收。

- 当前源码基线：main / f524611e3c2f2b064c765da0e63d061d035ad51b。
- 车机：API18 / C3ALFUS / 06.03.08600.H53.00060，实际解码器名称须在每次测试重新记录。
- 当前可连接的USB2修正版APK：9d8118b0d8a81043fff89f0fbd5e374354d3491f445cfb11eab6eed129f9d78d，包名com.shihab.diplay.repair1.geely8600。
- 参考：上游xikai6282/DiPlay-Geely-Android43的VERSION-HISTORY.md，当前远端main为48a8c8977fa5dc52a367ac4e1b5c5e18b897bbab。
- 上游默认源码仍是历史P12；P16/P22优化以其文档和后续实际可取得的产物核查为准。此计划是在当前源码重建对应行为，不冒称完整合并了P16源码或P22包。
- 架构保持USBMUX/iAP2控制、NCM/IPv6/TUN数据桥、TCP视频、UDP音频、MediaCodec/AudioTrack输出。技术栈保持Java/Kotlin、API18、现有Gradle/NDK与签名。
- 保留USB2平台/租约/保护检查、供电/配置和driver恢复边界；不修改系统持久属性、固件、CAN、权限、认证或USB读取线程。
- P16核心：触控TCP_NODELAY、视频接收缓冲/Socket、视频线程调度、积压恢复flush。
- P21补充：不存在的BYD HUD服务门控。与P16分别提交/记录；不自动扩展到导航Opus或其他平台。
- 音频细分诊断、异步日志、内存优化与JNI解密属于条件性的后续工作，不能包装为P16已经验证的能力。

必要性：当前源码缺少上述P16四项，设置页面无法补齐，需要在实际owner中修改。只局部修改既有链路；不移植整个旧APK/smali、不增加通用性能框架。

TDD Route：off。实施时补有意义的回归与失败路径测试，不强制RED/GREEN仪式。实现按顺序在同一分支推进；共享媒体恢复/线程行为改动按项目规则由新独立agent审查实际diff，真实车测仍由正常应用入口完成。

## 步骤1：锁定回退与可对比条件

1. 重新核对git状态与上述SHA。已有无关改动则保留；基线有变化时先重新比对这些文件，不覆盖他人工作。
2. 建议执行分支codex/geely8600-p16-performance，不在分析阶段创建。
3. 保留已安装USB2修正版及SHA/证书。测试包保持应用ID、原签名和数据格式；测试期可保持versionCode29以便同签名双向覆盖，使用不同versionName和SHA区分；正式版本另行确定递增code。
4. 版本建议依次USB3-P16A、USB3-P16B、USB3-P16C；在mobile/build.gradle.kts配置。校验器scripts/verify-geely-usb-apk.ps1目前只允许USB1/USB2，须显式扩展预期版本并让所有USB3包继续强制检查原USB运行时metadata，不能只扩大枚举后漏掉保护检查。
5. 固定同一手机、线缆、USB口、画面比例/分辨率、帧率、音频缓冲和音乐文件。保存实际协商值，不用界面标称值代替；第一轮不要同时降低分辨率。
6. 当前“不采集车机”约束下只做离线准备；执行车测前再恢复授权通道并记录原USB2基线，不把历史另一晚的统计当作严格A/B基线。

## 步骤2：建立最低限度可观测性

参照/修改：airplay/StreamReceiveStats.kt、media/VideoStats.kt、airplay/ScreenStream.kt、media/AndroidMediaSink.kt；路径均位于shared/src/main/java/com/shilapi/xcertplay/。

- 复用现有5秒聚合日志，增加恢复原因、flush次数/失败次数、重建次数、恢复后首个有效输出耗时。
- Socket启动时记录请求与实际接收缓冲、TCP_NODELAY；线程启动时记录tid和实际nice值，失败仅记录一次。
- 当前processMaxUs是处理总耗时，不是纯解密耗时。音频诊断若进入后续范围，再分出socket等待、解密、回调/入队，使用累计计数/固定桶聚合，禁止每包打印。
- 统计回调不得在热路径新增同步文件写入；复用已有AsyncDiagnosticLog的安全边界，不顺带改变旧日志内容/顺序语义。
- 先生成仅带诊断的基线候选，确认没有明显新增开销。测试辅助模拟时钟覆盖统计累计/窗口清零，不能将这些测试当真机计时结果。

## 步骤3：P16网络与触控优化（P16A）

### 3.1 触控事件Socket

文件：airplay/AirPlaySession.kt，acceptEvent()。

- 在server.accept()取得socket后、交给读写线程前设置tcpNoDelay=true。
- 保留现有setSoLinger、加密、写锁、顺序、退出及重连语义。
- 此改动主要减少车机发往手机的小触控事件等待；连接选项设置失败记录后保持原连接能力。
- 用本地loopback socket检查新建连接和重连均应用选项，并回归配对/事件加密测试。不能用回读布尔值证明触控已变流畅。

### 3.2 视频接收Socket

文件：airplay/ScreenStream.kt，listen()、accept()。

- 对未绑定的ServerSocket在WildcardBind.bind之前申请512*1024接收缓冲，使TCP握手前有机会协商较大的接收窗口。
- accept后设置/回读实际receiveBufferSize，并按P16设置tcpNoDelay=true；实际缓冲由内核决定，不要求恰好512KB。
- 不改sysctl或系统rmem上限，不删除现有视频积压限制，不因增加缓冲而允许无限排队。
- 必须准确解释：TCP_NODELAY控制本端发送的小包合并；对主要接收视频的车机Socket，它不控制手机的发送策略，也不等于关闭延迟ACK。不能单独宣称它改善入站视频。
- 维持原关闭/重连行为。测试覆盖设置失败仍可接收、重连重新配置、合法视频帧仍按顺序解密。

P16A验收：相同场景触控发包等待及响应感受对比；接收缓冲实际值有记录；连接、认证、画面均不回归。未达成时先检查运行包身份及实际选项，而非盲目加大缓冲。

## 步骤4：P16视频线程调度（P16B）

文件：ScreenStream.kt的run(Socket)，AndroidMediaSink.kt中VideoDecoder.run()。

- 只在两个工作线程自身的入口设置Process.THREAD_PRIORITY_URGENT_DISPLAY（-8），不要用Thread.MAX_PRIORITY代替。
- 使用独立的性能opt-in与已核实固件识别条件；复用现有身份判定，不借USB准备操作来启用性能。普通构建/其他固件默认保持原行为。
- 如需共用方法，仅增加一个小的、职责明确的兼容入口，不创建通用调度框架或复制多个全局开关。入口的上下文/flag按现有构造路径传递并检查调用方。
- 记录实际getThreadPriority(myTid())；权限/系统拒绝时保留原优先级，不调用su、不改系统调度参数。
- 不将USB、音频、UI线程全部提到同一高优先级。它们会竞争CPU，视频改善而音乐恶化则该批不通过。
- 同时对比P16A和P16B的触控、画面间隔、CPU、音频缺口与卡顿。没有收益或影响音频时撤回本批，不能把“设置调用成功”算性能通过。

## 步骤5：P16解码积压恢复（P16C，风险最高）

文件：AndroidMediaSink.kt的VideoDecoder.run()/recover()/configureDecoder()/drainOutput()，VideoDecodeQueue.kt及其测试。

现状：recover(reason)被积压、队列溢出、输入缓冲停滞、非法access unit、容量不足共用，直接releaseDecoder。不可全局替换成flush。

建议实现：

1. 在私有解码器范围以明确的恢复原因区分调用，避免依赖日志字符串识别行为。
2. 仅“帧年龄超过250ms/队列溢出”且平台匹配、codec有效、Surface和格式未变、已经有过有效输出时尝试flush。
3. 在解码器自己的线程执行；清理旧待解码帧，重置参考链并沿用现有关键帧请求节流。flush之后不能将任意P/B帧直接交给解码器。
4. flush返回后旧输入/输出索引全部失效；当前处理栈不再使用之前取得的索引。API18如使用缓冲数组缓存，必须重新取得；本轮不额外引入缓存优化。
5. 首次输出前、格式变更、Surface替换、非法输入、容量不足、未知平台、实际codec异常继续走原重建路径。首帧前flush可能丢失SPS/PPS，第一版选择保留重建而非新增CSD补发机制。
6. flush抛异常时立即有界降级为现有重建；若随后已提交新关键帧和输入、但超过有界时间仍无输出，也降级重建并停止本会话继续尝试flush。建议初始窗口2秒，明确是候选保护阈值，必须在该车实测调整；静止画面没有新输入时不能据此判故障。
7. 每次恢复记录原因、路径、耗时、后续首帧与降级原因；保留CSD、关键帧请求、断连和Surface生命周期行为。

必要测试：允许flush/禁止flush原因、首次输出前保留重建、flush异常、关键帧后无输出的有限降级、停止会话撤销等待、格式/Surface变更、参考帧门禁、队列溢出清理。复用VideoDecodeQueueTest的测试方式，必要时只提取一个可测试的私有恢复决策，不为了模拟MediaCodec引入大型框架。

真机必须至少实际触发一次积压恢复并恢复画面。可在隔离诊断构建中做一次有界的解码消费延迟来触发，记录该注入，不进入最终包；仅看正常播放时recoveries=0不算验收flush路径。

保留旧重建作为异常/生命周期恢复路径；本次只替代符合条件的积压恢复，不删除通用重建代码。

## 步骤6：P21补充——停止无效BYD服务绑定

文件：hud/BydHudBridge.kt initialize()/tick()/bindLocked()，检查BydNavigationOutputs.kt调用。

- 初始化时通过API18兼容的PackageManager检查指定SomeIp服务是否存在且组件/应用启用。
- 不存在/禁用时记录一次“不适用”，不启动该HUD定时任务，也不反复bindService。
- 服务存在时保留既有连接、重连、解绑和导航输出；不按“不是比亚迪”直接关闭全部导航功能。
- 不影响GeelyNavigationObserver等8600已有路径。包安装/启用状态变化如何重新检查按应用重新初始化处理，不引入常驻扫描。
- 测试覆盖缺服务、禁用、存在可用及后续重新初始化；车测核对300ms无效绑定日志停止。
- 此为P21范围，单独记录收益，不能因此宣称P16完整移植或音乐卡顿已修好。

## 步骤7：构建、检查与独立复核

每批只重跑受影响检查；最终候选再执行一次完整构建与回归。以下是未来执行命令，本次未运行：

```powershell
Set-Location D:\geely\DiPlay-Geely-Android43
$env:JAVA_HOME='D:\geely\analysis\build-tools\jdk-25.0.3+9'
$env:ANDROID_HOME='D:\AndroidSDK'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:HOST_OS='windows'
$env:PATH='C:\Program Files\Git\usr\bin;'+$env:JAVA_HOME+'\bin;'+$env:PATH
$env:DIPLAY_AUTH_ASSETS_DIR=(Join-Path (Get-Location) '.private\geely-usb-auth')
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleStandaloneGeelyUsb --no-daemon --max-workers=2 '-Pkotlin.incremental=false' --no-build-cache '-Dorg.gradle.java.installations.paths=D:/geely/analysis/build-tools/jdk-25.0.3+9'
git diff --check
```

执行前核对common模块测试task仍存在，若项目task变化先查实际任务，不能把未执行的测试写成通过。新增测试随上述任务运行；签名输入沿用已有本地路径，不提交内容。

候选检查：用已扩展版本识别的verify-geely-usb-apk.ps1校验包ID、版本、API18/v1签名、ARMv7及原USB opt-in/helper；APK中抽查新增方法/常量、证书、SHA和所有实际变更DEX。禁止把旧classes2或P16旧DEX直接覆盖当前APK，以免丢USB2修复。独立review覆盖共享调用方、兼容分支、日志、取消和恢复边界；模拟测试不替代i.MX6真实flush验证。

## 步骤8：分批实车对比与停止条件

建议顺序：USB2基线 → P16A网络/触控 → P16B线程 → P16C恢复 → P21门控。每批保留对应APK/SHA/变更/日志，出现退步时回到上一包。若用户现场时间有限，可提前离线构建候选，但不能用合并包推断单项贡献。

停车后用同一段已缓存音乐排除公网流媒体缓冲干扰；保持手机状态/画面设置相同。每轮先等待初始化结束，然后持续操作至少5分钟，关键场景重复3次：音乐连续播放、CarPlay列表滑动/页面切换、地图平移缩放、同样操作下的导航语音。随后做USB拔插、正常关车再启动、应用页面离开/返回；Siri/电话打断作为相关回归单列，不替代主场景。

| 项目 | 判定方法 |
| --- | --- |
| 连接能力 | 不需要手工ADB配置，首次/拔插/冷启动能重新进入CarPlay |
| 画面与触摸 | 用户确认响应改善；同场景统计无持续退步，无黑屏/花屏/恢复循环 |
| 视频数据 | 对比接收间隔、队列年龄、恢复/重建次数、恢复首帧耗时；静态画面低fps不判失败 |
| 计数边界 | shown是应用输出提交计数，touch2frame是应用侧指标，不能称为物理屏幕FPS/端到端延迟 |
| 音频 | 连续音乐无反复断续；对比UDP序号缺口、队列和播放供给，视频改善而音频变差不可通过 |
| flush | 真实发生一次受控恢复并回到正常视频，未触发时标为未覆盖 |
| 持续使用 | 通过主要场景后再持续播放15分钟，关注温升后退化；结果如实记录 |

出现认证回归、持续黑屏、重复重建、声音恶化、应用崩溃或USB重连丢失时停止该批，保存日志并覆盖回上一同签名测试包，不卸载清数据、不改系统USB配置补救后冒充通过。

## 步骤9：音乐仍卡时的独立后续

先对音频收包→解密→回调/入队→渲染分别计时，同时核对USB/NCM队列与线程等待。确认哪个阶段拖慢后再决定修改：

- 接收积压：检查消费速度、调度和丢包位置，不直接无限扩大UDP缓冲。
- 解密占主导：先核对对象分配/可安全复用方案；算法、nonce、AAD、tag校验与测试向量必须保持。JNI/NEON需独立方案，不属于此次P16移植承诺。
- 日志I/O占主导：按现有异步队列机制迁移热路径，验证顺序、收尾和轮转，保留原日志语义。
- 播放队列供给正常但仍断续：再查AudioTrack写入/缓冲与调度；不能把P21导航Opus修复当成USB音乐卡顿的现成答案。

P16完成与音乐彻底修复分别验收；性能目标未达到时保留未完成项，不以构建/单测数代替交付。

## 参考

- https://github.com/xikai6282/DiPlay-Geely-Android43/blob/48a8c8977fa5dc52a367ac4e1b5c5e18b897bbab/VERSION-HISTORY.md
- https://developer.android.com/reference/java/net/Socket
- https://developer.android.com/reference/java/net/ServerSocket
- https://developer.android.com/reference/android/os/Process
- https://developer.android.com/reference/android/media/MediaCodec
- 本仓库docs/GEELY-8600-USB2-ACCEPTANCE-20261027.md、docs/GEELY-8600-HANDOFF.md。

自审：当前只创建计划文档；没有实施代码、构建新包、安装、采集车机或提交推送。未来执行入口为本计划步骤1，先核对基线与用户当时授权。
