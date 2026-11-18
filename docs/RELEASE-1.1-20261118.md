# DiPlay 8600 · 1.1

发布日期：2026-11-18。标签：`v1.1.0`。

[GitHub Release](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/releases/tag/v1.1.0) · [SHA256SUMS](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/releases/download/v1.1.0/SHA256SUMS.txt)

## 相对 1.0 的变化

- 合入 `461301e` 记录的诊断功能：H53 无线连接读取原厂热点信息、请求自动开启热点，未保存自动连接选择时默认连接已选手机；保留用户已保存的关闭选择。
- 无线 AAC 使用直接 ADTS 输入、输出信息复用，复制 PCM 后先归还 codec 缓冲；增加 API18 播放头耗尽恢复。
- 保留 AAC 等待/解码、PCM 排队、启播/重缓冲及 `/feedback` 时序诊断，长日志异步分段写入。反馈计算公式保持原有行为。
- 沿用 1.0 的蓝牙媒体隔离范围与缓冲容量规划，USB 会话保留原解码路径。未合入导致明显断音的 CAP300 试验。
- 版本名称升为 `1.1.0-H53-LOG`，versionCode 由 29 升为 30，应用标签为“DiPlay 8600 1.1”。本轮相对诊断集成只调整版本身份和发布说明。

## 发布身份

目标为吉利星越 2019 款 8600、Android 4.3 / API18、BOARD `C3ALFUS`、固件 `06.03.08600.H53.00060`。
包名仍为 `com.shihab.diplay.repair1.geely8600`，签名与 1.0 相同。

| 项目 | 身份 |
| --- | --- |
| APK | [DiPlay-8600-v1.1.0-20261118-14d4ca1f228e.apk](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/releases/download/v1.1.0/DiPlay-8600-v1.1.0-20261118-14d4ca1f228e.apk) |
| 内部版本 | `1.1.0-H53-LOG` / versionCode 30 |
| 大小 | 4,023,514 字节 |
| SHA-256 | `14d4ca1f228ebf1de163154aa5df5f89e741a7fbb36eb9ad695800aa4c1b9939` |
| 签名证书 SHA-256 | `f2ea4c3ab5186fc4aa783c8f09c5a52a32f9fda8c4537283ca8d495e3f6c6d79` |

## 安装与回退

公开包不含独立认证资产，适用于保留既有运行时认证身份的覆盖安装。保留应用数据，不要卸载后重装。
无线连接核对原车保存的热点和已配对手机；自动开启未成功时可手动开启原车热点。
自动连接与开机启动应用是两个独立设置，升级不会覆盖用户已保存的关闭选择。

原始 [1.0 发布页](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/releases/tag/v1.0.0) 和附件保持不变。
1.1 的 versionCode 为 30，1.0 为 29，普通安装器可能拒绝降级。需经已授权 ADB 使用 `adb install -r -d <1.0 APK>` 尝试保留数据回退；不支持时停止，不直接卸载。

## 验证范围与已知问题

1.1 本轮执行 `:shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleGeelyUsb :mobile:lintGeelyUsb -PgeelyPerformanceStage=B`，Gradle 成功；未变模块复用已有任务结果。测试报告合计 988 项，985 项通过、3 项跳过，无失败或错误；mobile Lint 为 0 错误、6 警告。版本脚本与文档已通过独立只读审核，功能来源见[集成记录](DIAGNOSTIC-INTEGRATION-20261118.md)。

实际 APK 已核对包名、版本、无线试验开关、B 配置、API18 v1 签名及 ARMv7；单 DEX035 与 helper DEX035，未内置认证资产，LOG 诊断标记完整保留。公开源码凭据检查和 Git 差异检查通过。

此前无线视频声音明显落后画面，用户估计约半秒、偏差基本固定，1.1 尚未证明此问题改善。
300 ms 是计划启播阈值，实际 PCM 容量仍按原规划计算，既有车机诊断基线为 640 ms。
音画同步、热点关闭后的自动开启、USB/无线重连、音乐连续性、暂停恢复和长期稳定性仍需对本次具体 APK 实车验收。
