# 将最新主线合入视频低分配分支并打包

用户最终授权方向：将主分支内容合入当前 `codex/geely8600-hud-video-lowcopy-20261101`，在当前分支构建安装包，由用户随后上车验证。用户明确确认主分支HUD方案已经验证通过；此确认不扩展为本视频候选已经验证。

## 基线和合并

- 已fetch `origin/main`，固定提交 `4421675`，包含HUD默认开启与原车导航状态保护。未更新或推送主分支。
- 当前工作区此前有未提交HUD、USB/VPN及视频诊断。逐文件备份于 `D:/geely/output/video-main-merge-20261101/before`，显式选择68项相关路径保存为当前分支本地提交 `b4915f2`；其他原有未跟踪资料保留。
- 将 `origin/main` 合入当前分支。HUD源码、资源、测试、界面和manifest冲突按主线版本解决；五项媒体实现/测试冲突保留已审查视频诊断和低分配增量。
- 独立agent确认HUD与origin/main完全一致，USB/VPN/Controller/恢复策略/格式转换未增加主线外行为；视频诊断、低分配及GC脚本与合并前checkpoint一致；无未解决冲突。

## 新鲜验证

- 当前分支执行 shared/common回归及 `:mobile:assembleGeelyUsb -PgeelyPerformanceStage=B`，BUILD SUCCESSFUL / 1m12s。
- 共839项：836通过、3项既有host-JNI跳过、0失败/错误。测试数量包含主线及既有模拟/诊断测试，不全部归于本次合并。
- USB及HUD Trial APK检查通过：API18、ARMv7、v1签名、同包同签覆盖身份、认证资产、helper DEX及HUD类。签名基线为 `hud-default-on-20261101` APK。
- `git diff --check`和暂存差异检查通过；全量lint未重跑，既有lint问题不在此任务范围。

## 安装包

`D:/geely/output/video-main-merge-20261101/DiPlay-8600-USB3-B-HUD-video-lowalloc-20261101.apk`

SHA256：`264a86d1d4c538708c3c88bc71c97072458e15222f999a66a52419dfa7cabe1a`。

字节数：18715150。构建及校验日志同目录保存。身份仍为同包同签的USB3-B，包名/版本名可能复用，候选以文件名和SHA区别。

此包保留主线HUD默认偏好及原车导航状态保护；既有用户OFF偏好仍保留。只合并和打包，没有连接/安装车机、发送HUD或外部消息，也未推送。视频低分配实际收益及新组合的实车效果由用户后续验证，不能由构建/测试推定。
