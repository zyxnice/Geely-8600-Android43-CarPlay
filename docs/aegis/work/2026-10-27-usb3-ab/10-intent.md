# USB3 A/B 安装包交付

用户授权：现在实现 A、B，明早先安装 A。父计划：../../plans/2026-10-27-p16-legacy-abc-validation.md。
范围：A socket/可选调度、共同低频诊断、B媒体原生解密；保留USB2连接与原签名。C及车机操作不在本轮。
TaskStartSnapshot：D:/geely/DiPlay-Geely-Android43，main，f524611e3c2f2b064c765da0e63d061d035ad51b，与origin/main差异0/0，唯一worktree。无staged/unstaged代码；原有untracked为Legacy分析、docs/aegis/INDEX.md与plans/，保留不覆盖。无正在进行Git操作的迹象。
TDD off/skipped；仍执行密码运算、API18、socket和回退回归。交付须有独立审查、完整构建、APK静态校验和与USB2一致的签名；实车效果明确待验。
必要性与owner：设置不能实现socket/原生解密。既有会话入口只接线，新增狭窄性能配置与native owner，避免向大型Activity/MediaSink混入新职责。默认关闭普通构建，H53/API18身份和测试metadata双门控；A不打包B的native库。保留Java解密与旧优先级回退。
代码不自动提交/推送；本轮以两个本地可安装APK为交付目标。
