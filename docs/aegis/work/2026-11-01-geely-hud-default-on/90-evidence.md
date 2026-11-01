# HUD 默认开启修订

用户要求“HUD 默认改为开启”。在独立发布目录修改，不影响原工作区未提交视频实验。基线是已装并获本次用户可用反馈的trial2，SHA `67ab78082c032e6640e532e7c6436df3f942fc74ff2618784f7da4d95266482d`；代码基于main `4734110`。

## 行为与边界

- 独立SharedPreferences偏好默认true，沿用BydOutputSettings模式；手动OFF持久化。偏好与本控制器租约分开，后续tick不会重新授权被撤销的owner。
- UNKNOWN先等待原车结束事件或本控制器人工停车确认；ACTIVE始终阻断。UI开关表达用户偏好，状态文字表达运行状态，未知时显示单独确认入口。身份、数据新鲜度、来源接管和清除保护保留。
- 正常控制器关闭后，为下一实例按偏好准备pending；不跨实例保留人工空闲确认，不隐藏清除失败。迟到UNKNOWN/SENT/普通等待不能覆盖手动OFF。
- 不更改媒体、USB、VPN，不卸载原车高德。默认值修改不等于可跳过来源保护；没有把过去的trial2实车反馈作为本次修订验收。

## 复核与验证

独立agent审查发现并复核修复了“release掩盖CLEAR_FAILED”和“迟到UNKNOWN覆盖OFF”两项回归。新增偏好持久化、未知等待、真实Settings状态竞争回归；没有增加生产测试接口。

构建入口 `D:\geely\analysis\build_geely_hud_default_on_20261101.py`，隔离目录 `D:\geely\output\hud-default-on-20261101`，精确保留trial2媒体/USB/VPN源码；仅12项HUD代码、测试、资源差异。821项测试中818通过、3项既有跳过、失败为零；geelyUsb/debug构建以及API18/ARMv7/DEX035/同签覆盖/认证资产/metadata隔离检查通过。独立复核与`git diff --check`通过。shared既有全量lint问题未在本轮修复，不称全部静态检查通过。

最终候选 `DiPlay-8600-USB3-B-output3-vpn-usb-r2-hud-default-on.apk`，SHA256 `df2da88de1ef884b28a554c0a604037a663627d75285c942e8a522b12652ffaf`。本次正常入口实车UI、默认偏好和实际HUD输出尚未验证。用户当前车辆状态未确认，已询问是否停稳可短暂断开；本次尚未安装，也未触发HUD输出。恢复条件是用户明确确认现场状态、调试连接可用，再核对实际已装包与备份后覆盖。
