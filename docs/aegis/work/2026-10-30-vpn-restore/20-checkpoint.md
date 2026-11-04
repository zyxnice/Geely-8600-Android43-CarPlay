# 当前状态

- 已核对原厂 Vpn constructor/prepare/enforceControlPermission，UID1000 或 vpndialogs 才可授权；root 不直接放行。
- core.odex / framework.odex 重新反优化成功：setGid/setUid返回int、prepareVpn两个String、getLegacyVpnInfo签名核实。
- 实现、13项新增测试、UID1000真实API18 Binder/租约probe及独立复审通过。
- 两个独立源码树均全量回归与APK构建、签名/资产/覆盖身份校验通过；749/761项，各3项既有跳过、零失败。
- 独立模拟器正常入口验证完成：两包覆盖成功、首次VPN确认、取消拒绝、再次进入仍确认，无启动崩溃；平台不符未走恢复。模拟器已关闭。
- 两个本地修复分支已创建，代码补丁相同，仅原八个媒体文件有基线差异；原工作区main保留全部用户原改动，真实暂存区不变，不建立新worktree，不推送。
- 2026-10-30晚实车唯一核对原output3并覆盖对应候选，已安装SHA一致；用户确认CarPlay画面、触控、音乐正常。固定OEM只读prefs核对信任与当前真实boot一致，人工生产助手经原厂桥返回already_prepared，已独立验证。
- 用户明确今天不重启，未改boot/prefs。本次允许的其余验证完成；剩余下一次真实kernel boot自动恢复（新grant）、无VPN确认及CarPlay连接。output1本轮未装车；证据在D:/geely/output/vpn-restore-20261030/vehicle。
