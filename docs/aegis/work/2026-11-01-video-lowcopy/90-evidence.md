# HUD 基线上的视频低拷贝候选

日期：2026-11-01。分支：`codex/geely8600-hud-video-lowcopy-20261101`。

## 目标与边界

用户选择低拷贝优化及耗时/GC观测。保留当前未提交的 HUD、USB r2、VPN、output3 与既有视频诊断实现；不安装、不发布、不发送 HUD 消息。HUD 实现已本地构建，尚未实车验收，不能标记 HUD 功能已完成。

当前 HEAD 为 `aaec231f07425f7589b80975cae7129dea38c44a`。新分支承接工作区未提交内容，不创建基线提交。开始时文件哈希、Git状态和受影响文件副本保存在 `D:/geely/output/video-lowcopy-20261101/baseline/`。原 HUD 分支保持在原 HEAD，未提交内容位于当前工作区。

## 最小实现计划

1. 独立复核确认正常B接收已原地转换Annex B，下游复用同一数组；撤回无收益的直接写入方案。仅在B同步接收线程复用128字节header，C排队路径仍独占header；解码队列改为单次遍历计数，消除每次入队的临时帧列表。二者是低分配优化，不能声称消除了整帧拷贝。
2. `AndroidMediaSink` 保留线程、恢复、时间戳、输出选择、加密和Surface路径。`VideoPipelineStats` 增加准备耗时及仅该转换环节的字节计数；堆内存为进程瞬时值，不当作GC或分配总量。
3. GC从已有 Dalvik logcat 做离线分析，不调用全局分配计数开关或主动GC，以免观测本身改变待测性能。
4. 验证header分片读取、截断EOF、body独占及统计窗口；沿用队列容量/溢出/关键帧恢复回归，执行shared/common回归、APK构建和独立实际增量复核。

参照：`MediaCodecSupport.kt`、`AndroidMediaSink.kt`、`VideoDecodeQueue.kt`、`VideoPipelineStats.kt`及对应测试。此次不改变模块边界、公共协议或架构选型，不创建ADR。

## 验收状态

本地实现、回归、独立复核及候选包检查已完成；实车性能验收未做。

- shared 662项、common 162项，合计824项：821通过，3项既有host-JNI跳过，0失败/错误。最终回归日志 `output/video-lowcopy-20261101/build-final.log`；包含既有未提交诊断测试，不把数量全部算作本轮新增。
- 由HUD候选APK提取相同的两项认证资产到显式本地构建输入，未打印内容、未写入Git。最终候选构建日志 `build-candidate-final.log`。
- 候选 `DiPlay-8600-USB3-B-hud-lowalloc-probe1.apk`，SHA256 `9700a9e9c4e35877b35426d8c1467edbbe622427b12735ee3cf07dc90d28f201`。USB APK及HUD Trial验证脚本通过，包含API18/ARMv7/v1签名、同包同签覆盖身份、认证资产和HUD试验类检查。
- 独立agent复核了本轮相对开始快照的实际增量，无阻断项。已修正文档中的旧假设及计数口径。HUD、USB、VPN源文件的开始哈希保持一致。
- GC工具通过PID隔离、两段暂停、单段暂停、不识别GC记录样本断言。历史output3 PID6497文件有34条pipeline记录、0条匹配GC；这不是“没有GC”的证据。
- 未重新运行全量lint；继承HUD记录的全量lint问题未在本轮整改，不能称全部静态检查通过。

## 采样字段与使用

`INPUT_PREPAREUs`为MediaCodecSupport转换调用耗时，`INPUT_COPYUs`为获取/写入codec输入缓冲阶段，原有INPUT_WAIT/DEQUEUE和显示提交统计保持原语义。`copiedFrames/copiedBytes/annexBArrayBytes`只统计已完成codec-buffer复制的路径：被拒绝/等待关键帧的帧、网络/JNI/加密分配不在其内；`annexBArrayBytes=0`表示此环节复用已有数组，不是全链路零拷贝。`heapUsedBytes`为进程Java堆占用瞬时值，不是分配总量或GC计数。

保存正常业务操作时的 `logcat -v threadtime` 后，执行：

```powershell
./scripts/summarize-video-gc.ps1 -LogPath <日志文件> -ProcessId <应用PID>
```

工具只读分析已有文件。PID换了须分别分析；未识别或缺失日志不能推导无GC。`pauseSumMs`汇总暂停段，`collectionTotalMs`包含并发收集过程，二者不能混为一谈，也不能凭时间相关认定GC导致某次卡顿。未启用全局分配追踪、未主动触发GC。

离线测试不能证明API18硬解吞吐、GC减少、滑动体验或HUD实际可用。没有安装、连接车机、发送HUD广播、提交或推送；实车收益留待同场景对照。下一步先核对实时安装包，再按用户安排对比相同操作下的提交间隔、恢复次数、GC暂停和主观跟手程度。
