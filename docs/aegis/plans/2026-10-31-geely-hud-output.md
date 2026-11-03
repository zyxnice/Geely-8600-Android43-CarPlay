# 8600 HUD 接入续作

> 历史记录（2026-11-03分类核对）：下文保留该次试验／计划的事实与限制；“当前、最新、待安装、下一步”仅指记录当时，不代表今天的安装或验收状态。请先读[最新状态](../../GEELY-8600-STATUS.md)；本记录不授权继续执行旧现场操作。

目标：延续会话01a17711-981e-7b22-8fd2-1b4b1c337a93，将真实CarPlay导航的方向、下一路口距离和路名适配到吉利原厂HUD。保留已装USB两秒r2及VPN、output3媒体基线。上次只观测是前置工作，不代表HUD显示已实现。

TaskStartSnapshot：HEAD aaec231/main，当前USB/VPN及output4诊断dirty状态已读取；本次切到独立分支codex/geely8600-hud-implementation-20261031，原有未提交改动保留。原HUD分支de6e049保留。原生worktree工具拒绝父目录工作区下的嵌套Git仓库，未用shell创建worktree；改用同仓库新分支，打包以已装r2源码为基线，避免混入output4。

参照：原GEELY-8600-HUD.md和ADR-0002；Iap2RouteState/GeelyNavigationObserver、BydNavigationOutputs；DiPlayActivity设置section/toggle；固件AutoNaviReceiver.onReceive与AutoNaviRpc.setHud_NL/setNaviStatus。共享解析和Controller入口核查调用方，实际diff独立审查。

Change Necessity: code-change。旧观察器无车辆出口，当前USB分支未整合观测；移植既有解析/观测，再补受控原厂广播适配。TDD Mode off/skipped，仍补协议、互斥、清除和异常回归。ArchitectureReviewRequired yes，修订ADR-0002。

方案：显式发送到原厂AutoNaviReceiver，不直接写MCU/CAN、0x75a透传或USB特权桥。精确平台和原厂APK身份核对，默认只观测。实验输出须用户本次手动启用，不跨进程/车机启动恢复。2026-11-01修订：不再以原车高德进程存在判断冲突，改用外源导航生命周期和路线证据；UNKNOWN只在人工确认原车未导航的停车试验范围内允许尝试。已知导航接管时退出，不向新来源补发结束。仅本次独占试验仍成立时处理停止、失效、断开清除。强杀/断电后车辆清空没有证据，不声明安全TTL。

实车输出试验须独立授权并有停车/台架配合：手机高德提供元数据、原车导航退出，用户已知原厂导航模式副作用并确认恢复方式。缺字段、未知图标、无法编码路名不伪造成有效值。未授权时仅本地实现和模拟发送，不操作车辆。

步骤：移植观测并核查调用方；核查原厂字段、清除与身份；实现受控输出owner和用户入口；补回归和独立复核；基于r2独立构建/核验APK；报告实车未验。

验收分别记录本地协议模拟、实际手机数据、HUD显示、停止清除、原车接管与强杀恢复；本地通过只代表实验候选，不能写成日常可用。
