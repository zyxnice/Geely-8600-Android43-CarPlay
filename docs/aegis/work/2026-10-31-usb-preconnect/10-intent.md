# 先插 USB 再打开 DiPlay 的连接恢复

目标：针对用户报告的每天首次开车先插 USB、后开 DiPlay 无法识别 iPhone，以及重新插拔可恢复的场景，补齐连接前发现恢复；实车验收仍待现场执行。本次只改本地代码与候选，不安装、不推送发布。

TaskStartSnapshot：HEAD aaec231。工作区原有 VPN 恢复、output4 媒体诊断、测试和文档全部保留；git status 在修改前已读取。参照项目 AGENTS.md、RuntimeRecovery/RuntimeHelper/RuntimePolicy、IphoneUsbHost.discover 与策略测试。当前历史装车记录为 output3-vpn-fix，不能把工作区混有 output4 的 APK 冒充该基线。

Change Necessity: code-change。已就绪端口的空发现只轮询，没有枚举恢复；新增一次有条件的供电重接候选。CanonicalOwner：原有 USB runtime 兼容模块；发现层只传递是否有 Apple，不能据此授权复位，helper 必须重新检查总线。风险高：临时供电操作；实际 diff 需独立 agent 审查。TDD Mode: off；纯策略测试覆盖边界及回滚。

PatchShape：既有临时恢复路径扩展。Minimality：在现有 owner 增加固定动作，不新增开机服务、系统持久配置或任意 shell。最高风险反例：枚举刚完成时复位活跃设备；双重读总线和仅初始发现减少风险，但 sysfs 与写入没有原子性，不能声称竞态完全排除。保持既有停止租约、单飞、截止时间、故障保护与平台精确 guard。

因果状态：contributing / recurrence open。用户反馈证明重插有效；代码证明空发现无恢复。没有本次冷启动实时日志，不能认定唯一根因；若失败实际上已经发现 Apple，则本候选不会触发，需继续定位权限/USB配置/握手。

计划：实现初始发现空窗2秒后最多一次恢复；检查总线1无任何下游节点、Host/SDP/供电/故障正常后断电500ms再供电；策略和回滚回归；独立审查；以已记录装车的 output3-vpn 基线构建可识别候选并校验 API18、签名、认证资产；记录实车未验。用户明确8秒太长，后续改为2秒，独立于模式修复5秒间隔；实际触发仍有既有发现轮询与助手启动耗时。
