# 原厂导航接入候选

用户要求：新建分支，按原厂 HUD 与导航声音方式实现候选，上传 OSS。

分支：`codex/oem-navigation-integration-20261102`。
原分支 HEAD：`4b893ceaafc9be436c9531463579ced76914d74f`。
候选源码基线：`1909c95`，对应 output/hud-oem-sequence-20261102/source；基线提交单独保存候选与原分支差异。

范围：保留原厂 AutoNaviReceiver HUD 出口；导航语音使用已核实 stream 11，按 PCM 播放段管理焦点，提供实际 stream 音量入口。媒体/电话既有行为保留。

验收：单元测试、geelyUsb/debug 构建、APK 兼容/签名/身份资产/HUD 变体校验、独立代码复核、唯一名称上传 meice-test/geely/apk 并匿名完整下载核对 SHA-256。

非目标：安装车机、替换高德、修改系统、复制原厂 TTS 引擎。构建和上传不等于 HUD、音乐压低恢复及导航音量实车验收。
