# 1.0 诊断功能集成记录

2026-11-18，按用户要求将 `PCM300-FEEDBACK-LOG-20261117` 诊断功能合入原始 1.0 主线并推送仓库。
来源 APK SHA-256 为 `d8bd0fc23f75376fb8f0a8118586d9137fb56824546dc5546d520e2d3f9a6dd0`。
原始发布标签 `v1.0.0` 对应 `fe2daa9ada75`，其身份和验收记录见[正式发布说明](RELEASE-1.0-20261113.md)。

- H53 无线连接时读取原厂保存的热点配置并请求开启，等待真实 AP 状态及 `wlan0` 的原厂热点地址。开启前可能关闭当前 STA 连接，请求未被接受时尝试恢复 STA。
- 未保存自动连接选择时，匹配 H53 的集成版默认尝试无线连接已选 iPhone。已保存的关闭选择优先。车机开机启动应用仍由独立设置控制。
- 无线 AAC 采用直接 ADTS 输入、输出信息复用及复制 PCM 后先归还 codec 缓冲的处理，新增 API18 实际容量读取和播放头耗尽恢复。
- 日志记录输入等待、解码提交到输出、PCM 排队、启播、重缓冲及反馈采样时序。长 AAC 窗口日志异步分段写入。一次性音频抓取使用显式标记，每条流最多 512 包。
- 蓝牙媒体隔离沿用原始 1.0 对目标 H53 无线会话的匹配范围，保留对应回归测试。反馈回复沿原有时钟估算公式，新增日志用于核查实际播放进度偏差。
- PCM 容量采用原有 `MediaAudioBuffer` 规划，默认规划启播 300 ms，容量取启播加 200 ms 与系统最小缓冲乘 4 的较大值。本车诊断基线实测容量 640 ms、启播 300 ms。

默认构建采用 `geelyWifiTrial=true` 和 LOG 优化配置，包名仍为 `com.shihab.diplay.repair1.geely8600`，
内部版本为 `0.2.10-H53-1.0-DIAG-LOG-20261118`，界面标签为“DiPlay 8600 1.0诊断版”。
运行时无线试验功能限定 API18、`C3ALFUS` 和固件 `06.03.08600.H53.00060`。

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleGeelyUsb :mobile:assembleDebug '-PgeelyPerformanceStage=B'
```

已有覆盖设备使用已保留的运行时认证身份。独立认证输入按[构建说明](BUILD.md)显式提供。
`-PgeelyWifiTrial=false` 使用原 USB 车测构建配置，`-PgeelyWifiQuiet=true` 选择日志裁剪对照构建。

2026-11-18 的 CAP300 候选 `379dec3a0068` 实测出现明显断音，重播约 32 秒内有 6 次重缓冲，已回退。
集成版保留原有容量规划及覆盖此边界的回归测试。视频口型同步、热点关闭后的自动开启、USB/无线重连、
音乐连续性与暂停恢复仍需对集成后的具体 APK 实车验收。构建和单元测试只能证明其覆盖范围内的行为。

本轮仓库验证：

- `shared` 808 项、`common` 180 项单元测试：合计 985 项通过、3 项跳过，无失败。
- 默认诊断配置的 `assembleGeelyUsb`、通用 `assembleDebug` 及旧 USB 配置的 `assembleGeelyUsb -PgeelyWifiTrial=false` 均构建成功。旧配置产物确认版本为 `0.2.10-H53-USB3-B`，无线试验标记为 false。
- `:mobile:lintGeelyUsb` 完成，0 错误、6 警告；本次代码及新增 APK 检查脚本已通过独立只读审核。
- 默认诊断 APK SHA-256 为 `02718129d86938e8aa515460adadf5eb4abb11c2b211cd6c4a14eb33775a0b5f`，4,023,543 字节，API18 的 v1 签名验证通过；单 DEX035、无内置认证资产，诊断日志标记保留，实际 manifest 的无线试验标记为 true。
