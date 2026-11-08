# 画面流畅里程碑分支：USB 准备整合记录

## 目标、范围与授权

用户要求把已完成的APP Host＋CDP和内核NCM网卡启用代码合到画面流畅里程碑分支。
本次本地提交并整合 `milestone/usb-smooth-userspace-ncm-20261108`；不推送、不安装、不操作车机。
保留分支用户态NCM（kernel=false）及音视频、触控、缓冲、USB关闭/重试实现；只开启运行时准备metadata，
内核UP代码随已有可选分支合入，不改变当前传输选择。历史已验收APK及JSON记录不改。

## 开始状态与整合方式

- 目标起点：`c51d09c2b5180eb327498f393e4d898492d06d25`。
- 主工作区 `D:/geely/DiPlay-Geely-Android43` 在main/c690d8a，52个修改/未跟踪路径，无进行中的Git操作。
  任务开始已记录其状态和逐文件hash；不切换该工作区、不自动stash，不把其它未提交改动整合进来。
- 协调者创建本地独立克隆 `D:/geely/DiPlay-Geely-Android43-smooth-usb-integration-20261108`，
  从目标分支开始；该克隆origin指向本地原仓库，未访问远端。
- 来源：`D:/geely/output/kernel-ncm-auto-up-20261108/source` 已测试和独立审查的候选。
  按文件/hunk整合，Controller仅增加context参数，IphoneUsbHost只移除旧空发现回调，保留分支关闭流程修复。
- 此克隆保留为后续查看源码与构建证据的工作区；用户不再需要该checkout时可在干净状态清理。

## 行为与职责

RuntimeRecovery/Policy/Helper拥有运行时Host＋CDP；已就绪不启动helper，旧空发现断电协议已退役，
精确role=-1开机故障恢复保留。KernelNcmLink拥有当前枚举绑定及地址等待，Preparation拥有单飞/租约/取消，
UpPolicy和API18 helper只允许当前Apple cdc_ncm网卡UP。已UP只读，失败/关闭不DOWN，不改网络配置。
helper固定平台/boot/Host/健康供电及SDP/CDP，回读USB全身份、配置6、控制器/驱动、ifindex/MAC/接口后固定命令。
真实特权调用存在已提交ioctl无法撤销和名称调用的有限竞态；不把源码校验说成内核原子保证。

当前geelyUsb是runtime=true、kernel=false；默认用户态路径不会启动内核网卡。
系统默认与persist不改，Host运行时仍可能断开该口USB ADB，实车和ADB操作未执行。
BUILD/AGENTS为当前分支基线，ADR-0012为内核UP边界；11月8日里程碑原二进制记录保持历史语义。

## 验证进度

- 干净目标分支的RuntimePolicy基线测试通过，日志 `D:/geely/output/smooth-usb-baseline-20261108.log`。
- 整合后全量shared 771项，768通过、3项既有跳过；common 178项全部通过，合计949项、0失败/错误。
- geelyUsb与debug构建成功，Gradle退出码0，日志 `D:/geely/output/smooth-usb-integration-20261108.log`。
- 独立审查 `review_smooth_usb_integration` 读取全部实际diff/新增文件与调用方，规范/需求均无可操作发现；确认来源hash一致、默认用户态不误触内核UP、原关闭修复和历史APK JSON保留。
- 新增策略和Link/FakeIo测试覆盖身份替换、已UP跳过、失败不重复及SDP兼容。
  真实Preparation的超时/lease删除/挂起Binder单飞没有直接自动化覆盖，由源码检查；不声称原厂桥实测。
- 产物仅源码构建校验，不注入认证资产、不发布，不等同于历史实车包。

专用APK 5,587,932字节，SHA-256 `804fc18d3a872f30095fc803f729c72f4c61a338f3b3226cbee36a85b2164709`。
包名/版本仍为 `com.shihab.diplay.repair1.geely8600` / 29 / `0.2.10-H53-USB3-B`，minSdk18、包含ARMv7。
实际打包metadata runtime=true、kernel=false；普通debug包无这两个opt-in。
helper为DEX035，包含新UpHelper，与生成DEX的SHA-256一致：`8d59e903b55fe1a92b5326dd87dbc299f371f0a8bb5be04623898089bd5dbf36`。
APK无pk8/p7b身份资产。未执行需要认证/同签基准的完整实车包校验脚本，不将此包交付为可独立连接手机的车测包。
源码diff确认音视频、触控、native、NCM数据路径及里程碑JSON未变；IphoneUsbHost仅删除旧观察回调，Controller仅context参数。
提交前复查原main的HEAD/branch、52项状态及全部hash均与任务开始一致。

## 本地分支归档与未覆盖项

本任务提交以c51d09c为父；提交后从本地克隆快进更新原仓库未检出的目标分支，
最终提交号、祖先/树一致及主工作区保留情况写入本地 `D:/geely/output/smooth-usb-integration-receipt-20261108.json` 回执。
不更新远程跟踪分支、不推送。独立克隆作为源码与构建证据入口保留，认证资产/构建目录不入Git。
实车自动连接、首次成功率、音画触控回归及长期稳定性均待另行验收；本次本地合入不代表实车问题闭环。
