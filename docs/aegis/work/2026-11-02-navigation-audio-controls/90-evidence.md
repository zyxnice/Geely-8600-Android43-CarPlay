# 导航原厂路由默认值与混音设置

> 历史记录（2026-11-03分类核对）：下文保留该次试验／计划的事实与限制；“当前、最新、待安装、下一步”仅指记录当时，不代表今天的安装或验收状态。请先读[最新状态](../../../GEELY-8600-STATUS.md)；本记录不授权继续执行旧现场操作。

## 已安装基线

用户指定的 HUD b74a 基线之上，补齐 P21 Concentus 软件 Opus 解码；最终 SHA-256 `3b3867d388d63968ad4eb9ed7051bcd913dd97580000968df013c0e8d3d6c570` 已装车、实际安装文件核验一致。825项测试中822通过、3项既有JNI跳过；独立复核与三个APK检查通过。未混入另一 OEM-navigation 分支的焦点/音量改动。

## 实车导航无声定位

车机通过OSS下载并校验后同签安装，保留设置。导航实际协商 LPCM 8000Hz stereo；进入播放器前已接收包，AudioTrack创建抛出 `IllegalArgumentException: Invalid stream type`，writtenFrames=0。普通/自定义配置界面显示导航流7；另一只读字段旧 navigationStreamType=14 不能当作实际覆盖流。

通过正常设置入口选“CarPlay媒体＋导航播报”并保存、重连后，实际导航 streamOverride=11，trackState=1、playState=3，多次写入/播放指针增长且writeErrors=0。用户明确反馈“现在能听到了”。该现场未走Opus，软件解码缺口不能认定为此次无声根因。

## 用户要求的执行顺序

1. 固定当前已验证23／11方案为专用H53缺省设置，保留明确手动选择；构建、复核后先提交推送。
2. 然后增加独立“导航播报时压低音乐”开关，用户目标为导航和音乐原音量共存；保留来电/系统提示焦点处理，实车调试确认原厂策略是否仍会降音。
3. 新包先上传OSS并核验，由车机直接下载。停车确认后同签覆盖，不清数据。

第一步默认值源码和测试已准备，构建/推送结果后续追加；第二步尚未实施。重启、长期混音、电话/Siri恢复、HUD物理显示仍未验收。

第一步最终构建成功：827项测试，824通过、3项既有JNI跳过、0失败。三个APK检查和独立SharedPreferences正常入口测试通过；新APK SHA-256 `f86e7af010a25630b1d173f35896ec74b465d1eefdce51b160db9c4d09e8333d`。新默认包尚未安装，本段不将旧包人工选择配置的有声反馈当作新默认值已装车验收。

提交前的完整暂存diff空白检查发现Concentus原样源码中既有行尾空白与空格/Tab缩进告警；保留第三方归档字节一致性，不对库做无关格式化。排除该第三方目录的自有代码/文档diff空白检查通过；公开树凭据扫描通过。默认修订包已上传OSS并完整匿名下载，SHA一致。

## 第二步：独立混音开关

第一步已推送main `db4677d391e788c53a5fc2b080a8be55d48278df` 后才开始实现第二步。设置缺省false，普通保存/读取、Host恢复/新Intent加载、重建sink均传递该值；设置变化沿现有正常连接入口重连。

关闭降音时仅改变焦点归属：导航AudioTrack仍11，复用MEDIA23租约；开启时仍独立NAV11 MAY_DUCK。共享租约由最后一轨释放，电话/外部焦点丢失仍控制两轨音量。独立复核指出“PHONE/ASSISTANT先持有，再开始NAV”的反序风险，已增加明确deferred请求和反序回归；外部拒绝不自动进入deferred，最后中断轨释放才恢复仍活跃的deferred媒体请求。sink关闭先关闭Coordinator，禁止新acquire和恢复deferred，后续release继续清理。

用户明确“先备好新包，暂不安装”，本轮后续不操作车机。当前车机仍为3b3867及已保存的原厂有声配置；混音开关、共同播放不主动降音及来电实际恢复均待新包实车测试。最终构建与OSS核验结果后续追加。

最终混音候选构建成功：836项测试，833通过、3项既有JNI跳过、0失败/错误，三个既有APK检查通过。新的独立复核者随后在最终源码按正常Coordinator/AudioManager和SharedPreferences入口独立执行8项focus+2项preference测试，10项全通过；相关日志在本地`output/navigation-mix-20261102/reviewer-mix-entry-tests.log`。该targeted复跑会刷新测试XML，完整836统计以首次最终全量build.log和receipt为准。

最终APK SHA-256：`eaf488758e6f38efa4669a83488b78c7c10e4254033a71e378acf396a5c32028`，11,849,029字节。OSS上传200、匿名完整下载200，大小及SHA一致：[混音开关测试包](https://meice-test.oss-cn-shanghai.aliyuncs.com/geely/apk/DiPlay-8600-navigation-mix-20261102-eaf488758e6f.apk)。本轮未安装；[ADR-0008](../../../adr/ADR-0008-navigation-mix-focus.md)记录焦点归属、替代方案及撤销条件。
