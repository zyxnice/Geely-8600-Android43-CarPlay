# H53 讯飞仅按键候选的重现与恢复

状态：**实验性维护工具，已定向装车，但“仅按键”验收尚未通过**。用户报告唤醒词仍有回复，待排除与自动测试交叉后复测。见[现场状态与限制](../../docs/IFLYTEK-BUTTON-ONLY-20261104.md)。这些脚本不参与 DiPlay 构建，也不会由应用自动执行。

仅支持 C3ALFUS / API18 / `06.03.08600.H53.00060` 的指定原件。保留原 APK 和签名，不重新打包系统应用，不安装反编译分析 APK。这里只提交工具源码；原固件、反编译树、二进制产物、原始日志及本机连接资料留在仓库外。

## 文件与职责

| 文件 | 职责 |
| --- | --- |
| `build_button_only_odex.py` | 离线校验原件、解析方法并生成定长 ODEX；仅改7个唤醒入口和DEX Adler32，输出逐方法与字节差异回执 |
| `ButtonOnlyOdexProbe.java` | 独立 Dalvik 探针；跳过构造器，校验7个入口返回，不初始化语音引擎 |
| `probe.sh` | 先校验候选/探针哈希，再用原 APK 副本配候选 ODEX，在独立数据目录验证加载 |
| `install.sh` | 原件/平台/只读护栏、硬链接备份、原子替换和退出恢复只读；不重启进程 |
| `rollback.sh` | 校验并恢复原 ODEX，保留仍被映射的 inode，退出恢复只读；不重启进程 |
| `restart-speech.sh` | 检查平台、root及实时PID的完整进程名，仅向讯飞发送一次TERM |

## 离线重现

Python 3（禁止`-O`）；从自行持有的原厂固件取得 `system/app/XCSpeechClient.odex`。输入 SHA256 必须为 `84fa3b983da21d7c52d8703cea85efa467a4f70263eb0ff21b991026e35c4bd8`。`$OutputDirectory`必须是尚不存在的新外部目录；生成器拒绝覆盖已有证据。

```powershell
# 路径由操作者提供，输出必须在源码仓库外。
python scripts/iflytek-button-only/build_button_only_odex.py --original $OriginalOdex --output $OutputDirectory
```

预期候选2070512字节、720字节差异，SHA256 `00787efbd6edfe4a461c83f9c196e95b3c44108e11371baba8e4129b8d8f05cc`。原始DEX SHA1在优化ODEX中是原始身份，不对优化后的内容重算；仅更新DEX Adler32，依赖/优化区及其校验保持不变。依据见构建器回执的AOSP源码链接。

生成器回执的`runtime_validation=NOT INSTALLED OR TESTED`只描述**新生成的离线产物**；历史实车结果在状态文档中，不能由重现构建继承。构建器不是固件通用补丁器；不修改原文件。

## 独立 Dalvik 探针

使用能输出 Java 7 字节码的 JDK 和 Android D8；本次用JDK17及SDK build-tools35.0.0离线重编译并核验DEX035。所有编译产物放入外部目录。`$ClassesDirectory`为已创建的空专用编译目录，`$ProbeJar`为外部输出文件，`$D8`为本地已安装d8.bat路径。

```powershell
javac -source 7 -target 7 -d $ClassesDirectory scripts/iflytek-button-only/ButtonOnlyOdexProbe.java
& $D8 --min-api 18 --output $ProbeJar (Join-Path $ClassesDirectory 'ButtonOnlyOdexProbe.class')
$ProbeHash = (Get-FileHash -Algorithm SHA256 $ProbeJar).Hash.ToLowerInvariant()
```

APK/ODEX必须同名配对，API18不能直接把`.odex`作为普通classpath条目。探针编译包的ZIP时间戳可使hash变化，因此`probe.sh`要求显式传入操作者核验的探针hash，不复用另一构建的hash。候选ODEX和原APK仍严格固定hash。**禁止用原ODEX运行探针七个调用，它们在原件中会启动引擎。**

停车并获当次车机操作授权后，显式选择ADB和设备，不自动探测目标：

```powershell
& $Adb -s $Serial push $CandidateOdex /data/local/tmp/button-only-speech-20261104.odex
& $Adb -s $Serial push $ProbeJar /data/local/tmp/button-only-probe-20261104.jar
& $Adb -s $Serial push scripts/iflytek-button-only/probe.sh /data/local/tmp/button-only-probe-20261104.sh
& $Adb -s $Serial shell sh /data/local/tmp/button-only-probe-20261104.sh $ProbeHash
```

必须看到`BUTTON_ONLY_PROBE_PASS checks=7`，不能仅依据ADB进程退出码。旧adbd曾提前打印push进度，需等待传输结束并核对远端SHA。探针不等于实体按键或音频体验验收。

## 安装和回退（人工维护，不是自动更新）

仅在停车、目标和实际原件hash核验、隔离探针通过、安装/回退脚本独立审查后进行。通过既有`../invoke-geely-oem-command.ps1`执行固定脚本，其用法及平台边界见[维护总记录](../../docs/GEELY-8600-HANDOFF.md)。传入`-AdbPath`、`-Serial`，不要把示例中的占位符当成实参；先上传脚本并核对其本地/远端SHA。

1. `install.sh <候选完整SHA256>`只接受上述固定候选。首次安装会创建`/system/button-only-speech-backup-20261104-v2/`；**已经安装或该目录存在时不可重复安装，不要删除备份强行重试**。
2. 只有收到`INSTALL_EXIT:0:COMMITTED:1`，并独立确认目标hash和`/system`只读，才重新查询讯飞PID并执行`restart-speech.sh <实时PID>`。不要复用文档里的历史PID，也不要force-stop常驻包。
3. 新PID须稳定存活，映射inode须与目标ODEX一致；再验证空闲无输入、按键识别、结束/取消释放、语音词不唤醒、CarPlay音乐、连续多次和整机重启。仍须保持原厂连续对话等五项偏好关闭。
4. 启动失败或反复重启时执行`rollback.sh <候选完整SHA256>`。确认`ROLLBACK_EXIT:0`、原件hash和只读后重启实时讯飞PID，核对原件新映射与稳定性；不能将“已发送回滚”写成恢复成功。
5. 若回执丢失，先读实际目标hash与mount状态：原件、候选和未知三种情况分开处理。未知内容或连接丢失时不盲覆盖/重启。两个脚本保留的是一次试装与一次回退路径，不能视为任意次数幂等部署器。

原文件SHA见上文；原APK SHA256为`ad20379bdbbc5a70f6f0448bd093c7c0bd1ef0a5241b85a6f71d5b692bc21011`。备份目录0700，原件硬链接保持0644。不要对硬链接单独chmod，它会改变同inode权限。保留旧inode是为了避免原子替换后仍被进程映射的文件失去最后链接，导致remount只读报EBUSY。不要在运行期间清理保留inode。

## 诊断边界

不要在要求用户测试唤醒词时自动唤起语音，这会使结果无法归因。`fromservice=ecarx.voiceservice`只是Intent字符串，并非调用UID证明；该入口既接受按键，也接受应用请求/Binder远程请求。需结合`XCVoiceService_1.8.2`的广播日志与录音时间确认来源。CPU整数0%不是绝对零负载，也不是CarPlay断线已解决。
