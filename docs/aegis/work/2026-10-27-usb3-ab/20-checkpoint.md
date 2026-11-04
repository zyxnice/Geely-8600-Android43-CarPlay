# 执行检查点

- 2026-10-29 09:04 用户要求减少实车反复调试，已交付本机模拟：scripts/run-carplay-simulation.ps1，docs/CARPLAY-LOCAL-SIMULATION.md。仅新增3个测试源码文件/脚本/文档，未改生产代码或访问车机。真实加密控制/事件/视频/音频回环协议，触控HID→模拟手机→返回视频闭环，关键帧请求、重连、认证失败；测试以配对后合成密钥起步，不验证MFi/iAP2/USB/API18 native。虚拟时钟调用真实queue/reference/recovery/outputbatch，9场景覆盖突发、27ms暂停、400ms暂停、关键帧延迟、慢解码、多输出及静态恢复；调度/codec为模型，不是AndroidMediaSink/硬件或实车根因证明。最终3轮每轮8测试全部通过，0skip，压力结果一致，独立review_local_simulation闭环通过；证据output/carplay-local-simulation-final-20261029。output4仍未安装，output3车机状态未经本轮核验。后续先在本机验证策略，再安排必要的真实硬解/手感短测，不把模拟FPS当车机FPS。不提交最新候选。

- 2026-10-29 08:40 output4诊断包完成，用户授权继续解决积压/输出节奏并直接装车。新增完整消息接收/Surface提交每秒帧分布、间隔桶、100ms队列帧数/字节/帧龄采样和纯dequeueInput计时，不改变output3性能策略。独立review_output4_diagnostics两项统计边界问题已修复并复核通过。最终全量构建755项752通过3既有host-JNI跳过；API18/ARMv7/签名及覆盖身份校验通过。APK output/video-output4-20261029/DiPlay-8600-USB3-B-output4.apk SHA7291e682245ac58f122c1b13ef3cb48f036f4933e7aaa8877fe5249c97b73502，11521673字节。上传output4-literal.gz时15556由device变offline，push85023退出1/EOF，shell9978报closed；尚未组装/安装，仍output3。已请用户恢复车机ConnectBot，USB不用拔插；收到恢复后先真实shell验证、重新传输并校验，再安装。所有构建/传输进程已退出，无本轮collector。安装前GC证据preinstall-gc.txt：output3视频线程曾WAIT_FOR_CONCURRENT_GC20–27ms，同时网络也等待；仅证实干扰，不能称全部根因。采新包时logcat需包括dalvikvm:D并标记用户连续滑动窗口。详情../../../GEELY-8600-B-OUTPUT4-20261029.md。未提交/推送output2/3/4；output1仍main bc13d8f回退基线。

- 2026-10-29 08:25 output3已装车实际SHA579be029...，08:20:48 launcher/08:21:19.700控制器/08:21:31.440首帧，USB自动复用授权。用户反馈跟手同output1、画面连续性无明显改善，转只读检查缓存与帧率。TCP视频35378/UID10062 socket请求512KiB回读262142 bytes，rmem_max131071；60次100ms队列样本59次0/1次702bytes，无持续TCP堆积证据。B解码队列上限60帧/8MiB、250ms检查；当前未记录实际条数，队列帧龄已达289ms并恢复。活动窗口rx28.4–38.4/shown26.0–35.6fps（5秒平均，非物理FPS）；sink至Surface平均83–135ms若干窗口，静态长间隔不当积压证据。已有IDR追帧未命中。日志57480和54392均按命令行核对停止；当前保留output3，不新改参数/换包。详情../../../GEELY-8600-OUTPUT3-BUFFER-DIAGNOSIS-20261029.md。output1已推main bc13d8f，output2/3仍本地delta。后续应补实际队列深度和跨阶段帧时间关联，再单变量预算，不盲缩TCP或重复C10帧失败实验。

- 2026-10-29 用户授权先提交推送output1再实车output3。已精确stage output1快照（非当前output3 working tree），69文件累计依赖及文档，独立review_output1_publish无阻断；从index导出独立重建736项733通过3跳过。所有DEX/资源/helper/认证与原output1相同，16原生库仅.note.gnu.build-id不同，屏蔽后整个文件相同。提交bc13d8fff6904d9d8644ff592467627ff1eed5e6已推origin/main并ls-remote核对；output2/3源码8文件delta继续本地保留。15556实时可用，实际安装SHA仍131bbff7...output1；已开始上传output3差分包（本地还原逐字节等于579be029...原签名APK），需等待push进程结束及远端哈希校验再组装/安装，不以提前显示pushed当完成。发布证据output/output1-publish-20261029；output3现场证据接在output/video-output3-20261029。

- 2026-10-29 output3 离线候选完成，尚未装车：保留 output2 恢复/输出策略，B/H264 积压可从控制屏障前最新新鲜 IDR 恢复，B 输入单次等待 10→1 ms（总500 ms不变），增加接收至Surface提交/提交间隔指标。未改分辨率、帧率、音乐或USB。新增7测试，最终748项中745通过/3既有host-JNI跳过/0失败，122任务完整重跑；API18/ARMv7/签名/helper/认证及覆盖身份检查通过，独立review_output3无确定性问题。APK output/video-output3-20261029/DiPlay-8600-USB3-B-output3.apk，SHA579be0293691825d7d4e8d667b7da46fed5778265c3d6230394f984b1990ab2d，11,518,157字节。当前车机仍保留output1（本轮未操作车机）；下一步正常覆盖后按音乐、跟手、连续性分开记录原话，再决定保留/回退。详情../../../GEELY-8600-B-OUTPUT3-20261029.md。未提交推送；不得称流畅度已解决。

- 2026-10-29最新用户补充纠正排序：output1“虽然操作跟手，但是画面没有那么衔接，没有那么流畅”；output2“操作不太跟手，但是画面流畅度比Output1高”。必须分开操作响应与画面连续性：撤回旧记录里output2整体退步/output1全面最佳的概括。音乐两版均正常，当前仍是已恢复的output1，本轮未重新换包。output2继续保留作连续性对照；后续目标兼顾两者，先结合帧龄、提交间隔、恢复空窗与触控链路分析，不因主观反馈直接断言跳帧/缓冲根因，不把流畅度当清晰度。

- 2026-10-29 07:59顺序短测收尾：output2用户原话“目前这个版本音乐正常操作流畅度比上一个版本稍微差一点”，已归档。connect2无明显改善、input1略提升、output1明显跟手且当前最好、output2稍退步。按约定恢复output1：07:57:59覆盖Success，安装...-1.apk SHA131bbff7...已核对，07:59:09.970首帧，最后保留output1。所有4包及说明在/sdcard/DiPlay-validation-20261029且已SHA核验；采集61396已停止，本轮采集/安装/传输均结束，不停止应用或隧道。今日验收文档记录全部原话与证据；不再等待input1/output1/output2评价。用户要求全测完再统一优化，本轮未改代码；下一步以output1为基线分析output2恢复退步、约30秒首次打开等待、USB OUT延迟与冷启重复授权，清晰度及10分钟长测仍未验。不要把短测或恢复首帧写成全部性能目标完成。

- 2026-10-29 07:55：用户确认connect2“没有明显改善”；input1“音乐正常，体验有一点提升”；output1“音乐正常，操作明显跟手很多，这个版本是我当前觉得比较优秀的一个”。均已原话入今日验收表，output1现为优先回退版本。output1安装/实际SHA/首帧已确认，控制器至首帧18.560秒、launcher约52秒，单次不能归因启动改善。采集6300已停止；output2于07:55:04覆盖Success且实际SHA=e9e696...核验，07:55:11正常启动，当前采集48672/output2-live-logcat.txt。等待output2首帧及用户反馈，所有包已在车机，无需上传。先记录后统一分析，不逐版改码；若output2退化覆盖回output1。详见../../../GEELY-8600-CANDIDATE-ACCEPTANCE-20261029.md。

- 2026-10-29 07:51续接：四包已在车机/sdcard/DiPlay-validation-20261029（含INSTALL-ORDER.txt）完成SHA核验；当前实际安装input1，07:49:23首帧，控制器开始至首帧32.990秒含自动重试，launcher约63秒，不能混淆。input1已采真实触控/音乐，collector62120现已按命令行核对停止，无本次传输/采集遗留进程，应用/隧道保持。用户最新要求逐版先记录原话，全部测完统一优化；正在等input1体验答复，未装output1/output2，不提前切换。下一步收到评价先更新今日验收表，再本地pm install -r output1、核验installed SHA、启动新命名采集及正常入口；不重新传包，不要求无依据重插，不逐版改代码。

- 2026-10-29早现场续测：用户已停车、热点/ConnectBot开启，15556真实ADB可用。今天USB再次授权，用户确认勾选默认；默认1452:4776仍在，首次缺Apple设备随后恢复且用户期间重插，不能宣称冷启动免授权解决。connect2已设备端SHA核验；两次软件恢复约34秒均含OUT超时自动重试，视频仍250ms积压。用户要求先测完每版、记录原话，再统一优化，不逐轮改码。四个完整APK及INSTALL-ORDER.txt已预置/sdcard/DiPlay-validation-20261029，SHA全一致；input1于07:47:40覆盖Success且安装APK SHA核验，07:48:20正常启动，待用户体验反馈。output1/output2仅备包未安装。旧collector61868已停止，当前input1 collector62120；无传输/组装残留会话。后续按input1→output1→output2，收主观反馈后切换，记录[今日验收](../../../GEELY-8600-CANDIDATE-ACCEPTANCE-20261029.md)，不要改系统配置或把安装完成当体验通过。

- 2026-10-28晚B-output2离线交付：用户批准结合B-output与Legacy研究制作次日验证包。保留B输出合并/60帧队列/250ms阈值，启用与C小队列解耦的保留解码器恢复，增加B已接受输入停滞检测及缺IDR限频诊断。741测试(738pass/3既有hostJNI skip)，独立review_output2无阻断项，最终完整重打包及API18/v1/v2/同包同签/helper/认证核验通过。APK `D:/geely/output/video-output2-20261028/DiPlay-8600-USB3-B-output2.apk`，11515628字节，SHA `e9e696c16feb5eef20e867b864e7ec5285330eae6f6213e701d0c87ed9f33af7`。车机仍最后确认的B-connect2，未连接车机/服务器、未安装、未commit/push。所有构建命令已结束；下一步明早核对身份与包哈希、同签覆盖，实测进入耗时/触控/音乐/恢复。首次USB等待及清晰度未宣称修复。详见[交付与回退](../../../GEELY-8600-B-OUTPUT2-20261028.md)。

- 2026-10-28晚B-output1离线交付：用户明确要求基于深入排查做新版。在input1之上加入B已解码输出同批取最新（零超时、最多8输出、保留参考链）、事件与视频分段统计；不改分辨率/音频/USB策略，未操作车机。最终736测试(733pass/3既有hostJNI skip)，构建/API18/v1/v2/helper/同签核验通过；review_output_candidate提出的空EOS统计P3已修正并复查。APK `D:/geely/output/video-output-20261028/DiPlay-8600-USB3-B-output1.apk`，SHA `131bbff7e3f0ebaf4af994b70e93e4933d52da4b1fb3773edaf485718eb0c45c`。车机仍最后确认的B-connect2，input1/output1均未实车验收。详见[交付](../../../GEELY-8600-B-OUTPUT1-20261028.md)。无commit/push，构建命令已结束。

- 2026-10-28晚离线续进：用户结束今日车机调试，要求推进快速进入、清晰度、跟手；本轮未连接车机/服务器、未安装。以B-connect2为基线准备B-input1，增加队尾MOVE合并、保持日志文本的快速hex、NCM启动分段和显示几何诊断；不改画面参数和音频，不开启C。730测试(727pass/3原hostJNI skip)，构建/API18/v1/v2/同签/helper/认证核验通过，review_next_input无阻断项。APK `D:/geely/output/next-optimization-20261028/DiPlay-8600-USB3-B-input1.apk`，SHA `709a667b4228ac0a469a4cf1cda42f05762aacec8acfb87b7731979ccc1787d4`。车机仍B-connect2，待用户下次恢复现场；快速启动根因、清晰度和响应收益尚未验收。详见[后续计划](../../../GEELY-8600-NEXT-OPTIMIZATION-20261028.md)。无commit/push，本轮没有启动采集，构建命令均已退出。

- 21:09本轮现场验证收尾：connect2已于21:00:35成功同签安装，远端MD5/大小匹配；完整测试722项(719pass/3skip)、独立review_media_startup通过。两次正常断开→USB连接，无物理重插，分别36.540秒（含自动失败重试）和21.380秒；第二次OUT恢复后补发4301至TCP120ms，首帧21:05:01.730。用户确认“画面、音乐都正常”。保留当前connect2（SHA4c910ba924deb75337b289733f3bc814893f16bc6d0f0e1367f351f657331b47），不退回C。所有本次exec上传/组装/安装已结束，探针退出，核对命令行后仅停止collector54568；15556仍device。connect2-final.png与日志已保存。无重插恢复通过，快速连接整体部分完成，USB OUT启动十几秒原因仍未知；下一步在应用已授权fd内取失败errno/GET_STATUS与CDC状态，不能继续盲加重试。最终详情../../../GEELY-8600-MEDIA-HANDSHAKE-20261028.md。未commit/push。

- 20:56继续连接等待：connect1已安装并在远程正常“重试连接”后首轮15秒OUT失败、Activity自动下一轮成功，20:45:10.890首帧；用户明确无重插/手动重试。首轮OUT13秒后恢复但无媒体TCP的缺口仍在。connect2增加首次OUT恢复后500ms仍无TCP补发一次4301 + 25秒首TCP原子超时恢复，按attachment generation隔离。独立review_media_startup及722测试(719pass/3skip)通过，API18同签/native/helper/资源核验通过。APK SHA4c910ba924deb75337b289733f3bc814893f16bc6d0f0e1367f351f657331b47、13536859字节、MD5417ef6c04a3e3c6354b3af883de68938。正在上传connect2-literal.gz，exec session70484；需完成后推assemble-connect2.sh，生成新APK并回读MD5再pm install -r。用户不需要拔线，当前connect1保持可用。collector PID54568仍采集connect1-live-logcat.txt，不要杀其它ADB/转发。所有临时tun/usb-out-status探针已90秒自动退出并ps核实。详见../../../GEELY-8600-MEDIA-HANDSHAKE-20261028.md；OUT底层启动延迟原因仍未知。

- 20:32连接等待connect1候选已完成：NcmStartupWrite+NcmUsbBridge接线，首次完整NTB前最多15秒保留/重试单USB短包，边界/跨包负结果不重放，partial仍报错，取消checkOpen、就绪后旧deadline。review_ncm_startup已复核关闭阻断项。完整测试713项/710通过/3hostJNI跳过，构建成功，APK API18签名/资源及native/helper与父B一致。候选output/usb-handshake-20261028/DiPlay-8600-USB3-B-connect1.apk，16575651B，MD5e788dac843baa6a2ec8823f001bd3d3e，SHA273ac4ba8975237008c4febeadbe17d0e697908878b033a8467d0b2f44498042；gzip10784921B/MD51c4ca449e723a0b71792dd9dd6455850。正在15556上传，exec session33481，20:32约2.9MB；须等进程结束并远端hash再解压校验/安装。原B已在车机等媒体，用户要解决等待，不再让用户无依据反复拔插。探针v2已90秒退出，ps无残留，原工具/logcat关键回显保存在B-startup-drop-excerpt.md（明确摘录而非伪造原始logcat）；tcp与NS证据在tun-handshake-2.txt。安装后重新跑90秒v2探针并正常UI重连，验证NA/TCP/首帧及不拔线至少一次复连。尚未证明整个等待修复，不要夸大。

- 20:22用户明确追加修复B/C共有的“控制通道运行中等待很久”。已转系统排障，读取systematic-debugging及root-cause-claim-contract，尚未修改连接源码。B回退后认证成功仍卡住，实时fe80::2:7000监听但无已建立连接；历史SYN_RECV仅先前窗口。临时只读AF_PACKET探针output/usb-handshake-20261028/tun-handshake（源C同目录，MD5 c2caf60c0ebe68cea4953f2a9dd18477）已验传并通过OEM桥启动，90秒/250条自动退出，当前第1轮/data/local/tmp/tun-handshake-1.txt已chmod644，20:22正常UI重试已点。下步读取TCP7000/ICMP头部采样及freshapp日志定位；未改系统网络/USB，后续须确认探针退出。新排查记录GEELY-8600-MEDIA-HANDSHAKE-20261028.md。当前serial15556，无须Termius。

- 20:18 C软恢复短测完成：20:17六次keep-decoder真实触发，后续画面继续输出，所采音频无丢包/underrun/writeError；仍有解码队列overflow。用户明确“没有明显改善，跟b迭代看起来无差别”，判无明确收益，按父计划回退B。已验证车机B-usb-default包大小/MD5，覆盖安装Success，versionName恢复USB3-B、更新时间20:18:51。已启动正常入口，当前需确认回退后连接/播放再结束。C代码/文档不删除不提交，C完整证据output/usb3-c-20261028、交付文档GEELY-8600-USB3-C-DELIVERY-20261028.md。软恢复默认源码仍false，用户选项仍保留但B阶段会强制禁用C两项。未做flush/长测/冷启，画面问题未闭环。

- 20:16用户已手动打开软恢复；UI核实queue/soft/urgent/native=true、1920x720/60、音乐缓冲300ms。正常主页断开→USB连接后20:15:28新会话soft=true生效于配置，20:15:31认证成功，但仍等待媒体。已异步请求再重插并音乐/滑动30秒，当前等待答复；尚不能称软恢复实际触发或通过。证据C-soft-start.txt、C-soft-start-followup.txt。之前打开的音乐缓冲弹窗已点取消，未改变缓冲。保持15556及现有relay，不新增采集进程。

- 20:14 C已实车首帧和媒体播放，queue=true/soft=false样本已保存C-queue-sample1/2.txt。接收队列peak5/188914bytes/age61ms、无丢弃；下游解码队列反复overflow重建，流畅度未通过。音频所采窗口序号缺口/underrun/writeErrors/rebuffer均0，用户观感尚待回复。准备单变量soft=true测试，已通过正常UI返回DiPlay→设置，但ADB swipe/page-down无法滚动该固件，当前停在设置页顶部，已异步请用户手动开启“卡顿快速恢复（实验）”。开关尚未操作，新会话未生效；下一步收到回复后核对开关并正常断开重连，回读options后采样。不要盲改app prefs或重装。用户另已明确B重插没有授权弹窗。

- 20:08 C已校验完整传输及解压APK MD5并覆盖安装Success，系统更新时间20:07:18，运行PID5726。20:07:52确认stage=C/queue=true/soft=false、native/network/urgent=true；USB permission already granted、配置6复用、VPN attach成功，媒体尚待握手，已异步请求用户重插。新C日志C-start-live.txt；C-start.txt是替换前B旧日志，勿误判。最新B及C均1920x720/60请求（早前1536x576/30已不是最新），未主动改此参数。用户另确认B物理重插没有授权弹窗。下一步真实C首帧、队列/音频采样与用户感受，未验收C流畅度。

- 20:01 C候选已完成最终完整测试/构建/独立复核，705通过、3未提供hostJNI而跳过（native库与父B完全一致）。C APK SHA0a7d7933c39d2f97db348de1cf933ab0b598a44e94212c35135a1dd09497bc4f，默认queue=true/soft=false，保留USB权限修复。正在通过15556上传gzip封装（10.8MB，解压后16.6MB），需回读gzip MD5 87ea954b11b645260d9d36e892474f0f、解压APK MD5 7ea9ecc68356d3c1a6b2e63f17540a60后覆盖安装；尚未安装。详情../../../GEELY-8600-USB3-C-DELIVERY-20261028.md。15555已offline但15556正常，不让用户为调试再开Termius。

- 19:49用户确认授权修复可用且当前B已进入CarPlay/音乐正常，授权推进既定C。执行父计划2026-10-27-p16-legacy-abc-validation.md。C代码接收队列/可选软恢复分别开发，主agent集成options、版本、MediaSink回调与时间戳。首包queue=true/soft=false，继承B native/network和当前urgentVideo设置；不实施条件未满足的flush试验。USB默认过滤器保留。当前B回退包output/usb-permission-20261028/DiPlay-8600-USB3-B-usb-default.apk。现场基线已保存output/usb3-c-20261028/B-permission-baseline.txt（含静态心跳窗口，不据此断言掉帧）。

## C执行范围与验证门禁（2026-10-28）

- TaskStartSnapshot：root D:/geely/DiPlay-Geely-Android43，main，HEAD f524611e3c2f2b064c765da0e63d061d035ad51b，upstream 0/0，单工作树；既有A/B及转发、USB授权修改全部保留。原状态清单见output/usb-permission-20261028/taskstart-C-status.txt。不提交/推送未经本次明确要求的历史混合改动。
- Change Necessity: code-change。设置不能把同步读包/解密分离；新增有界接收队列及串行worker在ScreenStream职责内，恢复决策提取纯policy，MediaCodec操作仍仅解码线程执行。MediaSink新增默认方法串联失链和接收时间，现有调用不改变默认行为。
- Complexity/owner：ScreenStream接线+独立有界队列，AndroidMediaSink接线+独立恢复policy；不把新队列/策略塞进Activity，不改音频、USB、TCP协议、系统属性或全局调度。配置帧与视频顺序保持，nonce在接收时分配，关闭有回调门禁。
- TDD Mode off / skipped（沿原批准计划）；必须测试计数/字节/帧龄、nonce、取消/关闭/重连、参考链及软恢复降级。并发及共享接口实际diff由新的独立agent复核后才安装。
- C默认仅接收解耦，保留重建策略；软恢复默认关闭，可通过既有设置样式独立开启，说明下一次连接生效。仅旧解码器已输出后的短积压可保留实例等新IDR，新IDR提交后无输出有界降级且本会话禁用；不实施flush。
- 验证：相关shared/common回归、完整C构建、API18签名/helper/native/默认USB过滤器、同签回退核验；实车先C队列播放和音乐/触控，后单变量软恢复。未触发恢复标未覆盖，不能用单测或构建通过代替观感验收。

- 19:45授权补丁续接：已装B-usb-default候选，新PID20238，USB权限已复用且VPN不再弹；仍需用户答复物理重插测试。专用采集59032已核对命令行后停止，下一次按需新开，勿误停隧道。001/003设备和1452:4776默认关联仍在。媒体握手独立问题尚未解决。

- 19:43用户要求解决重复授权：确认原B缺Apple attach过滤器，新增geelyUsb资源overlay（CH341+Apple1452），构建/签名/独立审查通过并覆盖安装。候选output/usb-permission-20261028/DiPlay-8600-USB3-B-usb-default.apk，SHA01756ff986d7a5e7e0703e4b70385912926f3605774ff9a687a60c2ad6e87ac0；仍B版本，同业务逻辑/原生库。已请求用户物理重插验USB默认关联；VPN普通进程重开不再弹，跨系统重启仍需确认，未引入特权免授权。详见../../../GEELY-8600-USB-PERMISSION-20261028.md。当前专用采集PID记录在output/usb-permission-20261028/collector-pid.txt，结束需按CIM命令行核验后停止自己的采集进程。

- 19:32最新：用户重插/系统授权后19:24:39已进入媒体，初始22fps且两个视频线程nice=-8；用户19:25:48手动重新打开后再次等待。19:29:58正常UI重试未恢复，19:31仅重启DiPlay进程（PID884）后认证正常但tcp6仍SYN_RECV。现在不要再按“未枚举USB”处理，也不要把手动重开误记崩溃。已询问手机解锁及CarPlay提示，等待回复；主用15556和备用15555均保留。晚间详证见../../../GEELY-8600-USB3-ACCEPTANCE-20261028.md。未改源码/重装/改USB属性，优先恢复媒体再采稳定性能窗口。

- 19:17用户恢复Termius后15555真实ADB成功，已用此备用链路修复ConnectBot配置：密钥存在但未加载、启动加载未勾选、主机仍为任意内存密钥。现已勾选启动加载、明确选择专用密钥并回读；仅重启ConnectBot再点保存连接，19:21:04新15556真实ADB成功。接续优先15556，15555保留本次备用。用户反馈DiPlay仍等待，19:22日志显示USB3-B/native=true/urgent=true，1536x576/30fps；framework及sysfs均无Apple设备，role0、OTG sdp/power on。已请求解锁iPhone后同接口重插一次，等待回复，暂未取得晚间媒体流/线程优先级样本。

- 2026-10-28 19:12晚间续测：本机ADB初始无设备、15556无监听，已重新启动既有监督脚本（晚间日志output/adb-relay-20261028/workstation-supervisor-evening）。107实例Running、Cloud Assistant在线，19:13:48只读回查尚无127.0.0.1:15556反向监听；车机ADB未恢复，未改车机设置。已请求用户在车机ConnectBot点保存连接，手机只开热点、不用Termius。恢复后先验证B版本、urgentVideo实际配置及两个视频线程nice，再采同一音乐/列表滑动窗口。不要把电脑端监听恢复当作车机已连通。

- 2026-10-28当前任务已由用户转向转发稳定性。车机已安装官方ConnectBot v1.9.13，专用carrelay_8600账号、新回环端口15556，Termius停止后ADB真实shell仍可用；08:31服务端主动断线后3秒重新认证，用户关闭/恢复蜂窝数据后08:32自动重新认证。接续使用127.0.0.1:15556，不再要求用户重启Termius。本机监督脚本已审查/接管，08:33定向结束自有SSH子进程后5秒自动启动新进程、实际ADB恢复；旧15555隧道已停。车机与Windows开机自动恢复未实现，原版ConnectBot极端失败路径仍待验。完整配置与边界见../../../GEELY-8600-ADB-DIRECT-RELAY-20261028.md。B与urgentVideo保持原状态，本轮没有性能改码或重新安装DiPlay。

- 已核对基线和USB2回退包hash。
- 已完成：A/B实现、独立diff审查与问题修正、667项单测、Windows实际JNI/C测试、两个APK构建与签名/metadata校验、API18 ARM JNI及A→B→A覆盖安装。
- 候选与证据：../../../GEELY-8600-USB3-AB-DELIVERY-20261027.md，输出目录D:/geely/output/usb3-ab-20261027。
- 2026-10-28现场：已安装A，重插并通过重新枚举后的系统USB授权，取得音视频统计；用户确认仍明显卡顿，与昨天无主观改善。A流畅度未通过，画面优先级仍关闭。
- B已覆盖安装并启动，重插/授权后08:05进入媒体会话。PID11437，nativeMedia=true、urgentVideo=false，1536×576/30fps未变。10个窗口视频解密平均0.410～6.446ms、音频0.209～0.582ms，音频序号缺口0，无Java回退/认证失败；仍有长收帧间隔。等待用户音乐/画面分别反馈，整体流畅度未验收。B仍为当前版本，不重复安装；专用logcat已停止。C未实现。详情见../../../GEELY-8600-USB3-ACCEPTANCE-20261028.md。
- 无离线阻塞。未提交/推送，原有文档保留。恢复时先核对APK SHA及车机身份，不重建已核验包冒充同一候选。
- 最新用户确认B音乐改善非常多、画面仅稍好仍卡。拟用现有UI开启画面优先级作单变量对照；打开设置页时ADB再次无回应，尚未点开关，已请用户回Termius/恢复转发。恢复后先核对前台与开关实际状态，再操作；B仍安装，预期urgentVideo=false，需实时核实。
- 后续更新：用户恢复后已通过正常设置UI把urgentVideo设为true并回读，network/native仍true、分辨率80%。force-stop该应用再启动，新PID30798于08:14:55认证通过后ADB再次失联。当前保存urgentVideo=true但尚无线程nice/播放统计验证；已问用户画面/音乐状态并请恢复转发。恢复后先采新会话options和两个视频worker优先级，音乐恶化则UI关回false重新建连。未改代码、未安装新包。
