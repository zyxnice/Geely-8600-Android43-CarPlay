# 本地证据

输出根：D:/geely/output/vpn-restore-20261030。

- output1-build.log / output3-build.log：两套独立源码快照，shared/common全回归和geelyUsb B打包成功；output1-receipt.json 749项零失败3跳过，output3-receipt.json 761项零失败3跳过。
- output1-apk-verification.log / output3-apk-verification.log：API18 v1/v2、ARMv7、B媒体库、helper DEX035、认证两文件哈希以及同原基线包名/版本code/签名一致。
- candidate-trees.json：精确基线aaec231与两个构建树；output3六项历史源码SHA逐项匹配；两树媒体差异仅既有八文件。
- 新增GeelyVpnConsentPolicyTest10项、GeelyVpnConsentLeaseTest3项全部通过。
- 模拟器emulator-5580为通用Android4.3.1 ARM，并非原厂车机固件。独立VpnApi18Probe调用生产helper方法实际输出：PROBE_OK:restored:uid=1000；already_prepared:uid=1000；vpn_expired；vpn_cancelled；reset_to_boot_default。probe仅存在analysis目录，不打入APK。
- 独立复审vpn_review：原活动内建VPN接管问题已修复并补测；取消/超时与已发出Binder不能撤回的区别已落实至代码和ADR；未发现阻塞本地候选交付的新增确定问题。
- git diff --check通过。原main媒体脏改动不提交、不覆盖；用户暂存区不变。未连接车机、未发布或推送。

限制：原厂桥到助手完整调用、真实冷启动免弹窗、真实CarPlay连接及用户感受未验。legacy内建VPN同时启动有平台接口竞态；应用未观察的撤销紧接重启无法由旧信任标记可靠区分。
