# API 手册

本手册覆盖 `com.meowody.runtime` 的公开中心、基类、配置、句柄、Options、结果、状态枚举，以及 Cocos 加载入口。正文解释参数/返回值和生命周期；最后的 [完整签名索引](#完整签名索引) 从当前编译产物提取 public/protected 签名，便于按类搜索。没有列出 libGDX 父类全部 API 或 Cocos 内部模型的所有字段。

步骤教程见 [FRAMEWORK_TUTORIAL.md](FRAMEWORK_TUTORIAL.md)，MVP 实战见 [MVP_TUTORIAL.md](MVP_TUTORIAL.md)。API 片段中省略包名，实际导入路径见每个类的签名索引。

## 通用约定

| 约定 | 含义 |
| --- | --- |
| 时间 duration/timeout/delta | 秒；latencyMs/frameMs 为毫秒 |
| progressPercent | 0..100；完成包括失败/取消，不能用100判断成功 |
| DownloadHandle.progress | 0..1；未知总长度未完成时为0 |
| 音量 | 有限0..1 |
| 取消 | 抑制尚未派发的完成回调，不撤销已执行回调 |
| Options | 公开可写配置值；大多数提交时复制，不用修改原 Options 控制已开始的任务 |
| boolean 返回值 | 表示操作被接受或存在匹配项，具体含义见所在 API |
| 结果 ok()/succeeded() | 成功判断；仅“完成”并不保证成功 |
| AutoCloseable | 需要明确归属并 close；跨帧使用的资源不能过早 close |
| update/render/resize/dispose | BaseGame 已调用，正常业务无需重复调用 |
| record 类型 | 用 accessor，如 result.error()；LoadResult 则是 public 字段 resource/error |

默认由渲染线程使用。Event/Log 回调是同步，Net/Download/Resource/TimeSync 完成回调由 update 派发。读取后台句柄状态可观察进度，但 UI 改动仍放渲染线程。LogCenter 的日志写入和记录查询有同步保护。

## App

App 只公开 static 方法，无公共构造、get() 或实例字段。它内部管理一套游戏中心。

| 方法 | 参数/返回与调用位置 |
| --- | --- |
| audio/coroutine/download/event/net/pool/resource/save/time/ui/debug | 返回对应中心；需初始化后调用 |
| initialize(namespace) | 采用默认持久化目录初始化；Gdx 已启动后使用 |
| initialize(namespace,storageDirectory) | 指定 FileHandle 持久化根目录 |
| isInitialized() | 初始化前/退出后可安全查询，返回 boolean |
| storageDirectory() | 返回当前持久化根目录 FileHandle |
| update(delta) | 更新中心，delta 为帧真实秒数，不含 UI/Debug 绘制和帧末存档 |
| shutdown() | 关闭所有中心、清空单例、释放共享 Batch；重复调用无动作 |

未初始化访问中心会抛 IllegalStateException；重复初始化会抛异常并保留旧 App。通常交给 BaseGame 处理所有生命周期。

## ProjectConfig

| 字段/方法 | 默认值与语义 |
| --- | --- |
| standardWidth / standardHeight | float，1080 / 1920，逻辑设计尺寸 |
| desktopWidth / desktopHeight | int，540 / 960，Launcher 窗口像素尺寸 |
| viewportFactory | Supplier<Viewport>，默认 ExtendViewport(标准宽,标准高) |
| batchFactory | Supplier<Batch>，默认 SpriteBatch |
| createViewport() | 首次惰性创建，此后返回同一个 Viewport |
| createBatch() | 首次惰性创建，此后返回同一个 Batch；需要 GL 上下文 |
| dispose() | 释放缓存 Batch、清空两个引用，由 App 退出时调用 |

首次使用前配置。标准尺寸/Factory 修改不会改变已经缓存的实例。共享 Viewport 意味着共享 Camera；游戏世界要独立相机时，传自己的 Viewport 给 BaseScreen/BaseStage。Batch 仍可共用，要求顺序 begin/end 绘制。

## 基类

| 类/API | 用途和覆盖规则 |
| --- | --- |
| BaseGame.createFirstScreen() | abstract protected，返回首个 Screen |
| BaseGame.saveNamespace() | protected，默认 meowody，用于存档目录/旧 Preferences |
| BaseGame.storageDirectory() | protected，覆盖持久化根目录 |
| BaseGame.create() | final，初始化 App 和 Screen，不覆盖 |
| BaseGame.setScreen(next) | 替换并释放 previous，关闭非 persistent UI，重绑输入 |
| BaseGame.render/resize/pause/resume/dispose | 已有实现；覆盖时保留 super 调用 |
| BaseScreen() | 使用默认共享 Viewport/Batch |
| BaseScreen(Viewport) | 用自己的 Viewport、共享 Batch |
| BaseScreen.stage()/input() | 暴露 Stage/输入处理器；默认 input 返回 stage |
| BaseScreen.listen(type,callback) | protected，订阅在 hide/dispose 清理 |
| BaseScreen.track(handle) | protected，协程在 hide/dispose 取消 |
| BaseStage() | 默认共享配置 |
| BaseStage(Viewport) | 显式视口，共享 Batch |
| BaseStage(Viewport,Batch) | 两者显式提供，Batch 由调用者释放 |
| BaseStage.resize(width,height) | 像素参数，更新视口且居中相机，忽略非正尺寸 |
| BaseStage.dispose() | 幂等，仅释放 Stage；不释放借用的 Batch |

BaseScreen 还提供 Screen 标准方法。BaseStage 继承 libGDX Stage，可以 addActor、act、draw、setKeyboardFocus、getViewport/getBatch。独立 Viewport 的生命周期是普通 Java 对象，不需要 dispose。

## UI

### BaseUI / BasePopup

| API | 参数/返回/生命周期 |
| --- | --- |
| BaseUI() | protected；代码创建界面，随后 setRoot |
| BaseUI(jsonPath[,assetRoot]) | protected；取得 ManagerUIEditor 租约并创建 Group |
| setRoot(Group) | protected final；设置根节点/界面尺寸 |
| root() | 当前 Group |
| require(name,type) | 返回指定类型节点；缺失/类型错误抛 IllegalArgumentException |
| onClick(name,Runnable) | protected；永久节点点击绑定，适合构造时调用 |
| listen(Class,Consumer) / track(CoroutineHandle) | protected；本轮打开周期归属，关闭清理 |
| onOpen() / onOpen(Object data) | 默认带 data 的版本转调无参版本；配置本轮数据与订阅 |
| onOpened() / onClosing() / onClose() | 入场完毕/开始关闭/关闭回调 |
| onSuspend() / onResume() | 页面覆盖及恢复 |
| dispose() / isDisposed() | 最终释放/是否已释放 |
| BasePopup.close() | 通过 UICenter 请求关闭本弹窗 |

BaseUI 是 Group，不接收 App 或 Skin。不要直接 dispose 已由 UICenter 管理的 UI。缓存关闭会清理当前事件/任务作用域，但节点仍可复用；重新打开执行新的 onOpen。

### UICenter

| API 组 | 行为 |
| --- | --- |
| configure(path,options[,factory,presenter]) | 注册稳定路径；Options 复制；可创建业务 BaseUI/UIPresenter |
| open(path[,data]) | 打开并返回 UIHandle，默认非 multiple 路径复用活动界面 |
| openAsync(path,data,callback) | 排队/加载，回调在 update；取消抑制回调 |
| get(id)/find(path)/isOpen(path) | 活动实例查询；find 优先较新的实例 |
| close(id)/close(path) | 根据 id 或路径关闭，支持退场动画 |
| closeAll(includePersistent) | false 保留 persistent，true 全部关闭 |
| back() | 关闭符合 dismissOnBack 的顶层界面，返回是否处理 |
| progressPercent(path) | 指定路径打开进度 |
| activeCount/cacheSize/setCacheCapacity/clearCache | 活动数/缓存数/容量/释放关闭缓存 |
| show(BaseUI) | 快捷替换当前非持久页面 |
| openPopup/closePopup/closeAllPopups | 快捷管理模态 BasePopup |
| currentPage/popupCount/pageName | 便于查询页面与弹窗状态 |
| stage()/skin()/button(title,action) | 管理用 Stage 和代码控件工具；Cocos BaseUI 无需 Skin |
| bindInput(screenInput,debug) | 安装 Debug → UI → Screen 输入链，由 BaseGame 调用 |
| update/render/resize/shutdown/dispose/isClosed | 框架生命周期 |

UIOptions 字段及默认值在完整签名索引的源码字段段落中。kind 默认 WINDOW；multiple=false、persistent=false、cache=true、dismissOnBack=true、animationTimeout=30。assetRoot 指导资源基准，packUrl 指定远程 ZIP。enterAnimation/exitAnimation 是 Supplier<Action>，每次返回新 Action。

UIHandle：id/status/error/root/progressPercent 用于观察；isOpen 对 OPEN/SUSPENDED 返回 true，isFinished 对打开完成或终止返回 true；close/bringToFront 设置待处理请求；cancel 仅对 QUEUED/LOADING 有效。关闭缓存后 root=null，重新打开获得新 id。

### UIPresenter

配置 Presenter 时，UICenter 只向 Presenter 分发同名生命周期回调，不自动转发给 View。onAttach(root) 绑定 View；onOpen(data)、onOpened、onClosing、onClose、onSuspend、onResume 与界面生命周期对应。protected track(AutoCloseable) 注册连接，defer(Runnable) 注册清理；close final 且幂等，逆序执行。协程可用 defer(handle::cancel)。

## AudioCenter

| API 组 | 参数/返回/行为 |
| --- | --- |
| createChannel(name,options)/hasChannel/channels/channelOptions/removeChannel | 配置/查询通道；移除通道停止其声音，Options 查询为副本 |
| createBus(name)/setBusVolume/setBusMuted | 逻辑增益总线；默认 Master，无 DSP |
| setVolume(name,value)/setMuted(name,value)/setPaused(name,value) | 0..1增益/静音/通道暂停 |
| fadeVolume(name,target,seconds) | 真实时间渐变通道音量 |
| play(channel,path,options,finished) | 独立播放，返回 AudioHandle；finished 只在自然结束时触发 |
| play(channel,Supplier<AudioPlayer>,options,finished) | 自定义播放器工厂 |
| setPlayerFactory(Function<FileHandle,AudioPlayer>) | 替换资源路径播放器后端 |
| activeCount/stopChannel/stopAll | 活动数量及停止 |
| playSound/playMusic/stopMusic/stopSounds | 默认通道的快捷 API，playSound 返回声音 id |
| setSoundVolume/soundVolume/setMusicVolume/musicVolume | SFX/Music 快捷音量 |
| setMuted(boolean)/isMuted | 全局静音 |
| pause/resume | 应用级暂停/恢复，保留通道和单声暂停状态 |
| update/shutdown/dispose/isClosed | 框架生命周期 |

AudioChannelOptions 默认音量1、maxVoices16、stealOldest=true、bus=Master、muted/paused=false。AudioPlayOptions 默认音量1、fromSeconds0、looping=false。音量叠乘；全部有限0..1，不支持 pitch。

AudioHandle：id/status/isFinished、stop/pause/resume、setVolume、positionSeconds、seek。完成后 positionSeconds 返回0；seek 要求有限非负秒数。失败播放也返回句柄，检查 FAILED。AudioPlayer 实现应提供 play/volume/paused/playing/position/seek/stop/dispose。

## CoroutineCenter / Coroutine

| API | 行为 |
| --- | --- |
| start(Coroutine) | 默认缩放时间，返回 CoroutineHandle |
| start(CoroutineTask,unscaled) | true 使用真实时间；task.step 返回 true 表示任务完成 |
| delay(seconds,unscaled,action) | 到时执行一次 |
| until(condition,action) | 条件满足时执行一次 |
| stop(handle)/handle.cancel() | 取消，不撤销已经执行步骤 |
| size/empty/deltaTime/clear | 数量、空状态、当前更新 delta、取消全部 |
| Coroutine.make/then/pushStep | 创建链、添加立即步骤/自定义步骤 |
| yield(CoroutineYield)/yield(Coroutine) | 等待条件/子链，不能等待自身 |
| waitForFrames/waitForSeconds/waitUntil | 等待条件工厂，最初让出一帧 |
| moveNext/step/finished/empty/clear | 手动执行/状态/清空；已提交中心时不要重复手动推进 |

CoroutineStep.tick(double) 和 CoroutineTask.step(float) 的 true 均表示该步骤/任务完成。CoroutineYield.tick 首次返回 false，后续调用 protected update(delta)；reset 重新开始首次让出状态。一个 Yield 不要同时放入多个运行中的链。

## EventCenter

connect(type,Consumer)、connect(type,Runnable)、on(type,Consumer) 返回 EventSubscription。emit(Object) 精确类型同步分发；emit(Class) 创建无参对象后分发。clear(type) 清订阅但保留通道，clearAll/clear 全部清空；hasChannel/listenerCount 查询。

EventSubscription.close/disconnect 幂等，isConnected 查询。派发快照中已关闭订阅会跳过，新增加的订阅不插入当前轮。EventCenter 不负责跨线程切换。

## PoolCenter

| API | 行为 |
| --- | --- |
| create(name,type,factory,options) | 创建带 Poolable 钩子的对象池，返回是否成功 |
| createFactory(...,reset,options) | 可加入显式回收 reset |
| register(...,maxIdle)/registerPrefab(...) | 快捷普通/Actor 池注册 |
| acquire(name)/acquireAs(name,type)/obtain(name,type) | 获取，容量不足可能返回 null |
| acquire(name,parent) | Actor 自动挂载 Group；普通对象不适用挂载 |
| release(object)/free(object) | 回收归属对象；Actor 脱离父级并清根节点 Action |
| discard(object) | 丢弃对象，释放 Disposable |
| prewarm(name,targetIdle) | 补充至目标闲置数量，返回实际数量 |
| setCapacity(name,maxIdle,maxTotal) | 调整限制，缩减闲置对象可销毁多余项 |
| stats/names/contains/owns/isIdle/summary | 查询 |
| clearIdle(name) | 清理该池闲置对象 |
| remove(name,destroyActive) | 删除池；false 转移活动对象所有权，true 销毁 |
| clear/shutdown/dispose/isClosed | 中心清理/关闭 |

PoolOptions 默认 maxIdle128、maxTotal0（不限）、prewarm0。PoolStats：exists、idle、active、maxIdle、maxTotal，total=idle+active。Poolable.onPoolAcquire/onPoolRelease 用于业务重置；子节点 Action/监听需业务自己清理。

## ResourceCenter

继承 AssetManager，普通 load/get/unload、setLoader、isLoaded、getReferenceCount 等依然有效。框架新增 API：

| API 组 | 行为/所有权 |
| --- | --- |
| resolveFile(path) | 通过框架 resolver 查 FileHandle（含映射/内部路径） |
| loadNow(path,type) | 传统 AssetManager 同步 load；对应 unload |
| loadResult(path,type[,parameters]) | 同步类型化租约，不接受远程 URL；错误通过 result 返回 |
| loadAsync(path,type[,parameters],callback) | 本地或 HTTP URL 异步租约，每请求有独立引用 |
| loadPackAsync(url,entry,type[,parameters],callback) | ZIP 包内资源异步加载 |
| preloadGroup/preloadPackGroup | 命名资源组，返回 BatchLoadHandle |
| hasGroup/groupSize/releaseGroup | 查询或释放组所有成员引用 |
| getCached/getCachedPack/hasCached | 查询已加载值；返回值本身不产生新的租约 |
| evictCached/clearLru/setLruCapacity/lruCapacity/lruSize/lruEvictions | 管理中心缓存基础引用；其他租约可保持资源有效 |
| query/queryPack/queryGroup/progressPercent/isFinished | 加载状态与进度 |
| cancel/cancelPack/cancelAll | 取消相应请求，抑制回调 |
| clearCache | 释放组和 LRU 引用 |
| pendingCount/isIdle/isClosed/shutdown/dispose | 状态/生命周期 |
| typeFor(path) | 按扩展名猜常见类型，不替代显式业务类型 |

LoadResult<T>：public resource、error，ok 成功判断，close 释放引用且幂等。LoadHandle<T>：status/progressPercent/isFinished/succeeded/result；cancel 在完成前取消但不会释放已完成租约，close 同时取消并释放当前 result。

BatchLoadHandle：name/status/progressPercent/isFinished/succeeded/cancel/close。组由 ResourceCenter 保存，正常退出场景调用 releaseGroup(name)，而非只丢失句柄引用。

LoadProgress：exists/status/progressPercent/error，并提供 finished。同一路径必须对应同一种资源类型；加载器参数也应保持一致。同 key 共享工作，取消某请求不应破坏其他请求已持有资源。

## NetCenter

| API | 行为 |
| --- | --- |
| request(options,callback) | 通用请求，返回 NetHandle，callback 接 NetResult |
| get(url,Consumer<NetResult>) | GET 结果式回调 |
| postJson(url,value,callback) | POST UTF-8 JSON |
| get(url,success,failure)/send(Net.HttpRequest,success,failure) | 快捷适配 HttpResponse/Throwable |
| validUrl(value) | HTTP/HTTPS URL 检查 |
| configureMonitor(url,intervalSeconds,failureThreshold) | 配置服务联通探测 |
| checkNow/snapshot/isOnline | 主动检查/快照/成功状态 |
| subscribe(callback)/unsubscribe(id) | 联通状态监听 id |
| setMaxConcurrent/maxConcurrent/pendingCount/size/cancelAll | 并发/请求管理 |
| update/shutdown/dispose/isClosed | 生命周期 |

NetRequestOptions 默认 GET、空 headers/body、timeoutSeconds15、maxResponseBytes8MiB。NetResult.error/statusCode/headers/body/message；ok 检查错误，text 使用 UTF-8。headers 的值是 List<String>。HttpResponse 是旧快捷接口返回的文本包装，精确字段见签名索引。

NetHandle：status/result/isDone/isFinished/progressPercent/cancel。未结束 result.error 为 NOT_READY；取消抑制回调。网络监控状态与请求状态分开：NetStatus 描述接口/探测联通，NetRequestStatus 描述单个 HTTP 请求。

NetSnapshot：status、hasInterface、consecutiveFailures、lastCheckSeconds、latencyMs、probeUrl。联通状态由该 URL 测试，不能当所有服务的可用性保证。当前 HttpURLConnection 后端有桌面 PATCH/CONNECT 限制，见平台对照。

## DownloadCenter

| API 组 | 行为 |
| --- | --- |
| download(url[,options],callback) | 中心缓存目标，结果式 DownloadResult |
| download(url,target,options,callback) | 指定 FileHandle 目标 |
| download(url,target,success,failure) | 快捷 FileHandle/Throwable 回调 |
| getCachedPath/hasCached/removeCached | 以 URL+请求头区分缓存，Options 应与原请求一致 |
| getHandle/query/getResult/progressPercent/isFinished | 请求历史/进度/结果查询 |
| pause/resume/cancel(url,options) | 路径式控制；也可直接控制句柄 |
| setMaxConcurrent/maxConcurrent/pendingCount/size/isIdle/cancelAll | 并发及队列管理 |
| cacheDirectory/isClosed/update/shutdown/dispose | 路径/生命周期 |
| hash(byte[])/digest(Path) | SHA-256，digest 可能抛 Exception |

DownloadOptions 默认 resume/useCache=true、maxRetries2、maxRedirects5、timeoutSeconds30、retryDelaySeconds1、maxBytes512MiB、expectedSha256为空、headers为空。默认并发4。

DownloadHandle.bytes/total/retries/status/result/progress/progressPercent/isDone/isFinished/succeeded/failed/cancelled/paused/sizeKnown；total=-1 表示未知。pause/resume/cancel 控制工作，取消抑制回调。DownloadResult：path/error/httpStatus/message/fromCache，ok 判断成功。进度对象 DownloadProgress 的字段见索引。

提交到临时文件并校验后原子替换，失败保留旧目标；Range/If-Range 保证续传的一致性。相同活动请求去重，不兼容配置返回 BUSY。重试针对暂时网络/部分 HTTP 错误，不对确定的校验失败无限重试。

## SaveCenter

| API 组 | 行为 |
| --- | --- |
| configure(directory,options) | 配置目录/加密/限额，返回 SaveStatus；处理待写状态后再切换 |
| save(part,slot,value)/load(part,slot,type,defaults) | 分区槽写入/加载，后者返回 SaveResult<T> |
| inspect(part,disk) | true 磁盘；false 包含待写值，返回 Map 视图结果 |
| hasSlot/listSlots/listParts/partPath/directory | 数据和路径查询 |
| eraseSlot/resetPart | 删除槽/明确重置分区，按配置延迟写 |
| isPending/pendingCount/commitCount/lastFlushStatus | 待写分区数、提交数和最后结果 |
| flushPending/flushFrame(frame) | 立即尝试写/按帧合并，返回 SaveStatus |
| setFlushScheduler/requestFlush | 自定义 flush 调度；BaseGame 默认帧末处理 |
| saveObject/loadInto | DTO 字段快照及原子验证恢复 |
| saveModel/loadModel | SaveModel 版本迁移；返回 SaveStatus/ModelLoadResult |
| save(slot,value)/load(slot,type,fallback)/contains/slots/delete | default 分区快捷 API |
| shutdown/dispose/isClosed | shutdown 返回最终 flush 状态，dispose 为快捷生命周期 |

SaveOptions 默认 deferWrites=true、无密钥、maxFileBytes64MiB。encryptionKey 为0或32字节，后者 AES-256-GCM。SaveStatus.error/message/ok；SaveResult.error/message/data/usedDefault/ok；ModelLoadResult.error/message/savedVersion/currentVersion/migrated/ok。

SaveModel.saveVersion 给当前版本；migrateSave(fromVersion,fields) 每次迁移一步，返回 SaveStatus。较新存档版本拒绝读取。恢复字段前整体校验，忽略 static/transient/final；嵌套默认对象预先构造。

deferWrites 的 save 成功只表示接受修改，持久化结果查看 flush。缺少存档时 load 提供默认值，同时报告 MISSING/usedDefault。错误密钥和损坏文件不自动覆盖；resetPart 需业务明确选择。

## TimeCenter

| API 组 | 行为 |
| --- | --- |
| systemUnixTime/monotonicSeconds | Unix 秒/单调时钟秒 |
| now([source]) | TimeReading，默认 BEST_AVAILABLE |
| toDateTime(seconds,zone)/dateTime(zone,source)/format([zone,source]) | 日历转换/日期/格式化字符串 |
| networkSynced/networkSampleAge | 是否已有网络样本/样本单调时钟年龄 |
| syncNetwork(server,callback)/syncNetwork(options,callback) | 异步 NTP，返回 TimeSyncHandle |
| isSyncing/cancelSync | 同步状态/取消当前同步 |
| subscribeDaily(options,callback)/daily(hour,minute,callback,zone) | 每日任务，返回 DailySubscription |
| unsubscribe(id)/pause(id)/resume(id)/subscriptionCount/clearSubscriptions | 每日订阅管理 |
| setScale/scale/delta/realDelta/elapsed/realElapsed | 游戏时间缩放和真实时间，单位秒 |
| update()/update(seconds)/shutdown/dispose/isClosed | 时钟更新/生命周期 |

TimeReading.available/fromNetwork/unixSeconds；NETWORK 未成功同步时 available=false，BEST_AVAILABLE 会回退系统时间。DateTime 字段含日期、时分秒、星期、UTC 偏移，具体见索引。

NtpOptions 默认 port123、timeoutSeconds5、maxRoundTripSeconds3，server 必填。并行同步拒绝 BUSY；失败尝试限频64秒；成功样本会缓存复用。TimeSyncResult.error/message/server/unixSeconds/offsetSeconds/roundTripSeconds/ok。

DailyOptions 默认午夜0:0:0、系统时区、BEST_AVAILABLE、catchUpToday=false。DailySubscription.id/isActive/isPaused/cancel/close/pause/resume；每日最多触发一次，暂停期间错过的日期不逐日补发。DailyEvent/TimeSync 状态枚举在索引列出。

## LogCenter

全部 static，无 App 依赖。trace/debug/info/warn/error 支持 printf 格式；error 可带 Throwable。setMinimum 默认 DEBUG；setLimits(records,byteLimit,messageLimit) 默认2000条/4MiB/16Ki字符。

records 返回结构化 LogRecord 副本，history 返回格式化字符串副本，filter(levelMask,tag,text) 返回筛选记录。掩码 TRACE1/DEBUG2/INFO4/WARN8/ERROR16；tag 精确忽略大小写，text 在消息/来源中模糊匹配。

subscribe 返回 LogSubscription，close 断开；回调运行在写入线程。revision 供刷新检测，dropped 统计被缓冲限制淘汰记录；clear 清内容/淘汰计数但保留序列号语义。LogRecord 的 sequence/timeMillis/level/tag/text/source/thread/error/formatted 见索引。

## DebugCenter

| API 组 | 行为 |
| --- | --- |
| open/close/toggle/setOpen/isOpen | 打开/关闭详情 |
| setEnabled/isEnabled/capturesInput | 全局启用/输入拦截状态 |
| toggleKey | 默认 F12，同时 F3 可切换 |
| registerPanel(name,Supplier<String>) | 字符串动态面板 |
| registerPanel(id,title,Supplier<Actor>)/unregisterPanel | 自定义 Actor 面板和释放 |
| registerCommand/unregisterCommand/executeCommand | 命令注册/取消/排队执行；内置命令受保护 |
| parseCommand/complete | 静态引号转义解析/命令补全 |
| print/history/output/samples/stats | 输出、命令历史、性能样本和中心统计 |
| exportSave(part,slot,disk,raw)/exportLogs | 导出路径字符串；Save raw 导出整个分区文件 |
| setSceneRoot/input/stage | 节点检查根、输入处理器、共享渲染配置的 Stage |
| update/render/resize/shutdown/dispose/isClosed | 生命周期 |

DebugCommand record 参数顺序：name、description、usage、execute、complete。DebugCommandContext.line/args；DebugCommandResult.success/failure 创建结果。PerformanceSample 的时间、FPS、frameMs、heap 信息在索引列出。

DebugStage 使用1080×1920标准逻辑坐标，面板以世界尺寸布置，自有字体保持可读而不改变 Cocos 字体。UI 打开时仍可运行命令；setEnabled(false) 隐藏 HUD/面板并清待处理命令。

内置命令与具体用法见使用教程第18节，以及控制台 help 的实时输出。

## Cocos 加载入口

ResourceCenter 已注册加载器。ManagerUIParameters(assetRoot) 指定模板资源根；CocosLayoutParameters 支持元数据根及依赖加载参数，由 ManagerUILoader 内部使用。业务主要使用 BaseUI 或 ManagerUIEditor。

ManagerUIEditor.createGroup 创建独立树，disposeGroup(group) 清该实例生成资源；disposeOwned 清所有实例，由 AssetManager 模板释放管理。模板有共享依赖，业务不能在另一个 UI 使用它时提前 dispose。对手工 createGroup 的调用者，必须在释放布局租约前 disposeGroup。

PlistAtlasLoader 管理 Texture 依赖，PlistAtlas 可提供 AtlasRegion 查找，具体继承 TextureAtlas；托管页纹理由 AssetManager 释放，避免重复 dispose。CCExport 是导出布局数据结构，CocosLayoutReader 是解析入口；内部 parser/widget/model 应按实际导出类型扩展。

## 完整签名索引

以下签名来自当前编译产物。`public/protected` 被保留，参数名由 javap 省略，Java 标准包名/运行时包前缀缩短以方便阅读；类所在包单独列出。包含编译生成的 record 构造/accessor 与 enum values/valueOf，不包含 equals/hashCode/toString 等常规生成方法。索引列的是本类声明 API，父类 Stage/AssetManager/TextureAtlas 的方法见其实现和 IDE。

### App

包：`com.meowody.runtime`。源码：[App.java](../core/src/main/java/com/meowody/runtime/App.java)。

```java
public final class App {
  public static AudioCenter audio();
  public static CoroutineCenter coroutine();
  public static DownloadCenter download();
  public static EventCenter event();
  public static NetCenter net();
  public static PoolCenter pool();
  public static ResourceCenter resource();
  public static SaveCenter save();
  public static TimeCenter time();
  public static UICenter ui();
  public static DebugCenter debug();
  public static void initialize(String);
  public static void initialize(String, FileHandle);
  public static boolean isInitialized();
  public static FileHandle storageDirectory();
  public static void update(float);
  public static void shutdown();
}
```

### ProjectConfig

包：`com.meowody.runtime`。源码：[ProjectConfig.java](../core/src/main/java/com/meowody/runtime/ProjectConfig.java)。

```java
public final class ProjectConfig {
  public static float standardWidth;
  public static float standardHeight;
  public static int desktopWidth;
  public static int desktopHeight;
  public static Supplier<Viewport> viewportFactory;
  public static Supplier<Batch> batchFactory;
  public static Viewport createViewport();
  public static Batch createBatch();
  public static void dispose();
}
```

源码字段及初始化值：

```java
public static float standardWidth = 1080;
public static float standardHeight = 1920;
public static int desktopWidth = 540;
public static int desktopHeight = 960;
```

### BaseGame

包：`com.meowody.runtime.base`。源码：[BaseGame.java](../core/src/main/java/com/meowody/runtime/base/BaseGame.java)。

```java
public abstract class BaseGame extends Game {
  public BaseGame();
  protected abstract Screen createFirstScreen();
  protected String saveNamespace();
  protected FileHandle storageDirectory();
  public final void create();
  public void setScreen(Screen);
  public void render();
  public void resize(int, int);
  public void pause();
  public void resume();
  public void dispose();
}
```

### BaseScreen

包：`com.meowody.runtime.base`。源码：[BaseScreen.java](../core/src/main/java/com/meowody/runtime/base/BaseScreen.java)。

```java
public abstract class BaseScreen implements Screen {
  protected final BaseStage stage;
  protected BaseScreen();
  protected BaseScreen(Viewport);
  public InputProcessor input();
  public BaseStage stage();
  protected <T> void listen(Class<T>, Consumer<T>);
  protected CoroutineHandle track(CoroutineHandle);
  public void show();
  public void render(float);
  public void resize(int, int);
  public void pause();
  public void resume();
  public void hide();
  public void dispose();
}
```

### BaseStage

包：`com.meowody.runtime.base`。源码：[BaseStage.java](../core/src/main/java/com/meowody/runtime/base/BaseStage.java)。

```java
public class BaseStage extends Stage {
  public BaseStage();
  public BaseStage(Viewport);
  public BaseStage(Viewport, Batch);
  public void resize(int, int);
  public void dispose();
}
```

### UICenter

包：`com.meowody.runtime.ui`。源码：[UICenter.java](../core/src/main/java/com/meowody/runtime/ui/UICenter.java)。

```java
public final class UICenter implements Disposable {
  public UICenter();
  public Skin skin();
  public BaseStage stage();
  public TextButton button(String, Runnable);
  public boolean configure(String, UIOptions, Supplier<BaseUI>, Supplier<UIPresenter>);
  public boolean configure(String, UIOptions);
  public UIHandle open(String);
  public UIHandle open(String, Object);
  public UIHandle openAsync(String, Object, Consumer<UIHandle>);
  public UIHandle get(long);
  public UIHandle find(String);
  public boolean close(long);
  public boolean close(String);
  public void closeAll(boolean);
  public boolean back();
  public boolean isOpen(String);
  public float progressPercent(String);
  public int activeCount();
  public int cacheSize();
  public void setCacheCapacity(int);
  public void clearCache();
  public void update(double);
  public void show(BaseUI);
  public void openPopup(BasePopup);
  public void closePopup(BasePopup);
  public void closeAllPopups();
  public BaseUI currentPage();
  public int popupCount();
  public String pageName();
  public void bindInput(InputProcessor, DebugCenter);
  public void render(float);
  public void resize(int, int);
  public boolean isClosed();
  public void shutdown();
  public void dispose();
}
```

### BasePopup

包：`com.meowody.runtime.ui`。源码：[BasePopup.java](../core/src/main/java/com/meowody/runtime/ui/BasePopup.java)。

```java
public abstract class BasePopup extends BaseUI {
  protected BasePopup();
  protected BasePopup(String);
  protected BasePopup(String, String);
  public void close();
}
```

### BaseUI

包：`com.meowody.runtime.ui`。源码：[BaseUI.java](../core/src/main/java/com/meowody/runtime/ui/BaseUI.java)。

```java
public abstract class BaseUI extends Group implements Disposable {
  protected BaseUI();
  protected BaseUI(String);
  protected BaseUI(String, String);
  protected final void setRoot(Group);
  public Group root();
  public <T extends Actor> T require(String, Class<T>);
  protected void onClick(String, Runnable);
  protected <T> void listen(Class<T>, Consumer<T>);
  protected CoroutineHandle track(CoroutineHandle);
  public void onOpen();
  public void onOpen(Object);
  public void onOpened();
  public void onClosing();
  public void onSuspend();
  public void onResume();
  public void onClose();
  public void dispose();
  public boolean isDisposed();
}
```

### UIHandle

包：`com.meowody.runtime.ui`。源码：[UIHandle.java](../core/src/main/java/com/meowody/runtime/ui/UIHandle.java)。

```java
public final class UIHandle {
  public long id();
  public UIStatus status();
  public String error();
  public BaseUI root();
  public float progressPercent();
  public boolean isOpen();
  public boolean isFinished();
  public void close();
  public void cancel();
  public void bringToFront();
}
```

### UIKind

包：`com.meowody.runtime.ui`。源码：[UIKind.java](../core/src/main/java/com/meowody/runtime/ui/UIKind.java)。

```java
public final class UIKind extends Enum<UIKind> {
  public static final UIKind PAGE;
  public static final UIKind WINDOW;
  public static final UIKind MODAL;
  public static final UIKind OVERLAY;
  public static UIKind[] values();
  public static UIKind valueOf(String);
}
```

### UIOptions

包：`com.meowody.runtime.ui`。源码：[UIOptions.java](../core/src/main/java/com/meowody/runtime/ui/UIOptions.java)。

```java
public final class UIOptions {
  public UIKind kind;
  public boolean multiple;
  public boolean persistent;
  public boolean cache;
  public boolean dismissOnBack;
  public double animationTimeout;
  public Supplier<Action> enterAnimation;
  public Supplier<Action> exitAnimation;
  public String assetRoot;
  public String packUrl;
  public UIOptions();
  public UIOptions copy();
}
```

源码字段及初始化值：

```java
public UIKind kind = UIKind.WINDOW;
public boolean multiple, persistent;
public boolean cache = true, dismissOnBack = true;
public double animationTimeout = 30;
public String assetRoot, packUrl = "";
```

### UIPresenter

包：`com.meowody.runtime.ui`。源码：[UIPresenter.java](../core/src/main/java/com/meowody/runtime/ui/UIPresenter.java)。

```java
public abstract class UIPresenter implements AutoCloseable {
  public UIPresenter();
  protected void track(AutoCloseable);
  protected void defer(Runnable);
  public void onAttach(BaseUI);
  public void onOpen(Object);
  public void onOpened();
  public void onClosing();
  public void onClose();
  public void onSuspend();
  public void onResume();
  public final void close();
}
```

### UIStatus

包：`com.meowody.runtime.ui`。源码：[UIStatus.java](../core/src/main/java/com/meowody/runtime/ui/UIStatus.java)。

```java
public final class UIStatus extends Enum<UIStatus> {
  public static final UIStatus QUEUED;
  public static final UIStatus LOADING;
  public static final UIStatus ENTERING;
  public static final UIStatus OPEN;
  public static final UIStatus SUSPENDED;
  public static final UIStatus EXITING;
  public static final UIStatus CLOSED;
  public static final UIStatus FAILED;
  public static final UIStatus CANCELLED;
  public static UIStatus[] values();
  public static UIStatus valueOf(String);
}
```

### UITransition

包：`com.meowody.runtime.ui`。源码：[UITransition.java](../core/src/main/java/com/meowody/runtime/ui/UITransition.java)。

```java
final class UITransition extends Action {
  public boolean act(float);
  public void setActor(Actor);
}
```

### AudioCenter

包：`com.meowody.runtime.audio`。源码：[AudioCenter.java](../core/src/main/java/com/meowody/runtime/audio/AudioCenter.java)。

```java
public final class AudioCenter implements Disposable {
  public void setPlayerFactory(Function<FileHandle, AudioPlayer>);
  public AudioCenter(ResourceCenter);
  public boolean createChannel(String, AudioChannelOptions);
  public boolean hasChannel(String);
  public boolean createBus(String);
  public boolean setBusVolume(String, float);
  public boolean setBusMuted(String, boolean);
  public Set<String> channels();
  public AudioChannelOptions channelOptions(String);
  public boolean removeChannel(String);
  public boolean setVolume(String, float);
  public boolean setMuted(String, boolean);
  public boolean setPaused(String, boolean);
  public boolean fadeVolume(String, float, double);
  public AudioHandle play(String, String, AudioPlayOptions, Runnable);
  public AudioHandle play(String, Supplier<AudioPlayer>, AudioPlayOptions, Runnable);
  public int activeCount(String);
  public void stopChannel(String);
  public void stopAll();
  public void update(double);
  public long playSound(String);
  public void playMusic(String, boolean);
  public void stopMusic();
  public void stopSounds();
  public void setSoundVolume(float);
  public void setMusicVolume(float);
  public float soundVolume();
  public float musicVolume();
  public void setMuted(boolean);
  public boolean isMuted();
  public void pause();
  public void resume();
  public boolean isClosed();
  public void shutdown();
  public void dispose();
}
```

### AudioChannelOptions

包：`com.meowody.runtime.audio`。源码：[AudioChannelOptions.java](../core/src/main/java/com/meowody/runtime/audio/AudioChannelOptions.java)。

```java
public final class AudioChannelOptions {
  public float volume;
  public boolean muted;
  public boolean paused;
  public int maxVoices;
  public boolean stealOldest;
  public String bus;
  public AudioChannelOptions();
  public AudioChannelOptions copy();
}
```

源码字段及初始化值：

```java
public float volume = 1;
public boolean muted, paused;
public int maxVoices = 16;
public boolean stealOldest = true;
public String bus = "Master";
```

### AudioHandle

包：`com.meowody.runtime.audio`。源码：[AudioHandle.java](../core/src/main/java/com/meowody/runtime/audio/AudioHandle.java)。

```java
public final class AudioHandle {
  public long id();
  public VoiceStatus status();
  public boolean isFinished();
  public void stop();
  public void pause();
  public void resume();
  public boolean setVolume(float);
  public float positionSeconds();
  public boolean seek(float);
}
```

### AudioPlayOptions

包：`com.meowody.runtime.audio`。源码：[AudioPlayOptions.java](../core/src/main/java/com/meowody/runtime/audio/AudioPlayOptions.java)。

```java
public final class AudioPlayOptions {
  public float volume;
  public float fromSeconds;
  public boolean looping;
  public AudioPlayOptions();
}
```

源码字段及初始化值：

```java
public float volume = 1, fromSeconds;
public boolean looping;
```

### AudioPlayer

包：`com.meowody.runtime.audio`。源码：[AudioPlayer.java](../core/src/main/java/com/meowody/runtime/audio/AudioPlayer.java)。

```java
public interface AudioPlayer extends Disposable {
  public abstract void play(float, boolean);
  public abstract void volume(float);
  public abstract void paused(boolean);
  public abstract boolean playing();
  public abstract float position();
  public abstract boolean seek(float);
  public abstract void stop();
}
```

### VoiceStatus

包：`com.meowody.runtime.audio`。源码：[VoiceStatus.java](../core/src/main/java/com/meowody/runtime/audio/VoiceStatus.java)。

```java
public final class VoiceStatus extends Enum<VoiceStatus> {
  public static final VoiceStatus PLAYING;
  public static final VoiceStatus PAUSED;
  public static final VoiceStatus FINISHED;
  public static final VoiceStatus STOPPED;
  public static final VoiceStatus FAILED;
  public static VoiceStatus[] values();
  public static VoiceStatus valueOf(String);
}
```

### CoroutineCenter

包：`com.meowody.runtime.coroutine`。源码：[CoroutineCenter.java](../core/src/main/java/com/meowody/runtime/coroutine/CoroutineCenter.java)。

```java
public final class CoroutineCenter {
  public CoroutineCenter();
  public CoroutineHandle start(Coroutine);
  public boolean stop(CoroutineHandle);
  public float deltaTime();
  public boolean empty();
  public CoroutineHandle start(CoroutineTask, boolean);
  public CoroutineHandle delay(float, boolean, Runnable);
  public CoroutineHandle until(BooleanSupplier, Runnable);
  public void update(TimeCenter);
  public int size();
  public void clear();
}
```

### Coroutine

包：`com.meowody.runtime.coroutine`。源码：[Coroutine.java](../core/src/main/java/com/meowody/runtime/coroutine/Coroutine.java)。

```java
public final class Coroutine implements CoroutineTask {
  public Coroutine();
  public Coroutine then(Runnable);
  public Coroutine pushStep(CoroutineStep);
  public Coroutine yield(CoroutineYield);
  public Coroutine yield(Coroutine);
  public boolean moveNext(double);
  public boolean step(float);
  public boolean finished();
  public boolean empty();
  public void clear();
  public static Coroutine make();
  public static CoroutineYield waitForFrames(int);
  public static CoroutineYield waitForSeconds(double);
  public static CoroutineYield waitUntil(BooleanSupplier);
  public static CoroutineYield waitUntil(BooleanSupplier, DoubleConsumer);
}
```

### CoroutineHandle

包：`com.meowody.runtime.coroutine`。源码：[CoroutineHandle.java](../core/src/main/java/com/meowody/runtime/coroutine/CoroutineHandle.java)。

```java
public final class CoroutineHandle {
  public CoroutineHandle();
  public void cancel();
  public boolean isDone();
}
```

### CoroutineStep

包：`com.meowody.runtime.coroutine`。源码：[CoroutineStep.java](../core/src/main/java/com/meowody/runtime/coroutine/CoroutineStep.java)。

```java
public interface CoroutineStep {
  public abstract boolean tick(double);
}
```

### CoroutineTask

包：`com.meowody.runtime.coroutine`。源码：[CoroutineTask.java](../core/src/main/java/com/meowody/runtime/coroutine/CoroutineTask.java)。

```java
public interface CoroutineTask {
  public abstract boolean step(float);
}
```

### CoroutineYield

包：`com.meowody.runtime.coroutine`。源码：[CoroutineYield.java](../core/src/main/java/com/meowody/runtime/coroutine/CoroutineYield.java)。

```java
public abstract class CoroutineYield implements CoroutineStep {
  public CoroutineYield();
  public final boolean tick(double);
  protected abstract boolean update(double);
  public void reset();
}
```

### EventCenter

包：`com.meowody.runtime.event`。源码：[EventCenter.java](../core/src/main/java/com/meowody/runtime/event/EventCenter.java)。

```java
public final class EventCenter {
  public EventCenter();
  public <T> EventSubscription connect(Class<T>, Consumer<T>);
  public <T> EventSubscription connect(Class<T>, Runnable);
  public <T> EventSubscription on(Class<T>, Consumer<T>);
  public void emit(Object);
  public <T> void emit(Class<T>);
  public boolean hasChannel(Class<?>);
  public void clear(Class<?>);
  public void clearAll();
  public int listenerCount();
  public void clear();
}
```

### EventSubscription

包：`com.meowody.runtime.event`。源码：[EventSubscription.java](../core/src/main/java/com/meowody/runtime/event/EventSubscription.java)。

```java
public final class EventSubscription implements AutoCloseable {
  public boolean isConnected();
  public void disconnect();
  public void close();
}
```

### PoolCenter

包：`com.meowody.runtime.pool`。源码：[PoolCenter.java](../core/src/main/java/com/meowody/runtime/pool/PoolCenter.java)。

```java
public final class PoolCenter implements Disposable {
  public PoolCenter();
  public <T> boolean create(String, Class<T>, Supplier<T>, PoolOptions);
  public <T> boolean createFactory(String, Class<T>, Supplier<T>, Consumer<T>, PoolOptions);
  public <T> void register(String, Class<T>, Supplier<T>, Consumer<T>, int);
  public <T extends Actor> void registerPrefab(String, Class<T>, Supplier<T>, Consumer<T>, int);
  public Object acquire(String);
  public Object acquire(String, Group);
  public <T> T acquireAs(String, Class<T>);
  public <T> T obtain(String, Class<T>);
  public boolean release(Object);
  public void free(Object);
  public boolean discard(Object);
  public int prewarm(String, int);
  public boolean setCapacity(String, int, int);
  public PoolStats stats(String);
  public boolean contains(String);
  public boolean owns(Object);
  public boolean isIdle(Object);
  public Set<String> names();
  public boolean clearIdle(String);
  public boolean remove(String, boolean);
  public String summary();
  public boolean isClosed();
  public void clear();
  public void shutdown();
  public void dispose();
}
```

### PoolOptions

包：`com.meowody.runtime.pool`。源码：[PoolOptions.java](../core/src/main/java/com/meowody/runtime/pool/PoolOptions.java)。

```java
public final class PoolOptions {
  public int maxIdle;
  public int maxTotal;
  public int prewarm;
  public PoolOptions();
  public PoolOptions copy();
}
```

源码字段及初始化值：

```java
public int maxIdle = 128;
public int maxTotal;
public int prewarm;
```

### PoolStats

包：`com.meowody.runtime.pool`。源码：[PoolStats.java](../core/src/main/java/com/meowody/runtime/pool/PoolStats.java)。

```java
public final class PoolStats extends Record {
  public PoolStats(boolean, int, int, int, int);
  public int total();
  public boolean exists();
  public int idle();
  public int active();
  public int maxIdle();
  public int maxTotal();
}
```

### Poolable

包：`com.meowody.runtime.pool`。源码：[Poolable.java](../core/src/main/java/com/meowody/runtime/pool/Poolable.java)。

```java
public interface Poolable {
  public abstract void onPoolAcquire();
  public abstract void onPoolRelease();
}
```

### ResourceCenter

包：`com.meowody.runtime.resource`。源码：[ResourceCenter.java](../core/src/main/java/com/meowody/runtime/resource/ResourceCenter.java)。

```java
public final class ResourceCenter extends AssetManager {
  public ResourceCenter();
  public ResourceCenter(DownloadCenter);
  public synchronized void setErrorListener(AssetErrorListener);
  public FileHandle resolveFile(String);
  public <T> T loadNow(String, Class<T>);
  public <T> T finishLoadingAsset(String);
  public <T> LoadResult<T> loadResult(String, Class<T>);
  public <T> LoadResult<T> loadResult(String, Class<T>, AssetLoaderParameters<T>);
  public <T> LoadHandle<T> loadAsync(String, Class<T>, Consumer<LoadResult<T>>);
  public <T> LoadHandle<T> loadPackAsync(String, String, Class<T>, Consumer<LoadResult<T>>);
  public <T> LoadHandle<T> loadAsync(String, Class<T>, AssetLoaderParameters<T>, Consumer<LoadResult<T>>);
  public <T> LoadHandle<T> loadPackAsync(String, String, Class<T>, AssetLoaderParameters<T>, Consumer<LoadResult<T>>);
  public boolean update();
  public static Class<?> typeFor(String);
  public BatchLoadHandle preloadGroup(String, Map<String, Class<?>>, Consumer<BatchLoadHandle>);
  public BatchLoadHandle preloadPackGroup(String, String, Map<String, Class<?>>, Consumer<BatchLoadHandle>);
  public boolean hasGroup(String);
  public int groupSize(String);
  public void releaseGroup(String);
  public <T> T getCached(String, Class<T>);
  public <T> T getCachedPack(String, String, Class<T>);
  public boolean hasCached(String);
  public boolean evictCached(String);
  public void setLruCapacity(int);
  public int lruCapacity();
  public int lruSize();
  public long lruEvictions();
  public void clearLru();
  public LoadProgress query(String);
  public LoadProgress queryPack(String, String);
  public LoadProgress queryGroup(String);
  public float progressPercent(String);
  public boolean isFinished(String);
  public boolean cancel(String);
  public boolean cancelPack(String, String);
  public void cancelAll();
  public int pendingCount();
  public boolean isIdle();
  public boolean isClosed();
  public void clearCache();
  public void shutdown();
  public void dispose();
}
```

### BatchLoadHandle

包：`com.meowody.runtime.resource`。源码：[BatchLoadHandle.java](../core/src/main/java/com/meowody/runtime/resource/BatchLoadHandle.java)。

```java
public final class BatchLoadHandle implements AutoCloseable {
  public String name();
  public LoadStatus status();
  public boolean isFinished();
  public float progressPercent();
  public boolean succeeded();
  public void cancel();
  public void close();
}
```

### LoadHandle

包：`com.meowody.runtime.resource`。源码：[LoadHandle.java](../core/src/main/java/com/meowody/runtime/resource/LoadHandle.java)。

```java
public final class LoadHandle<T> implements AutoCloseable {
  public LoadStatus status();
  public float progressPercent();
  public boolean isFinished();
  public boolean succeeded();
  public LoadResult<T> result();
  public void cancel();
  public void close();
}
```

### LoadProgress

包：`com.meowody.runtime.resource`。源码：[LoadProgress.java](../core/src/main/java/com/meowody/runtime/resource/LoadProgress.java)。

```java
public final class LoadProgress extends Record {
  public LoadProgress(boolean, LoadStatus, float, Throwable);
  public boolean finished();
  public boolean exists();
  public LoadStatus status();
  public float progressPercent();
  public Throwable error();
}
```

### LoadResult

包：`com.meowody.runtime.resource`。源码：[LoadResult.java](../core/src/main/java/com/meowody/runtime/resource/LoadResult.java)。

```java
public final class LoadResult<T> implements AutoCloseable {
  public final T resource;
  public final Throwable error;
  public boolean ok();
  public void close();
}
```

### LoadStatus

包：`com.meowody.runtime.resource`。源码：[LoadStatus.java](../core/src/main/java/com/meowody/runtime/resource/LoadStatus.java)。

```java
public final class LoadStatus extends Enum<LoadStatus> {
  public static final LoadStatus QUEUED;
  public static final LoadStatus LOADING;
  public static final LoadStatus SUCCEEDED;
  public static final LoadStatus FAILED;
  public static final LoadStatus CANCELLED;
  public static LoadStatus[] values();
  public static LoadStatus valueOf(String);
}
```

### NetCenter

包：`com.meowody.runtime.net`。源码：[NetCenter.java](../core/src/main/java/com/meowody/runtime/net/NetCenter.java)。

```java
public final class NetCenter implements Disposable {
  public NetCenter();
  public NetCenter(BooleanSupplier);
  public static boolean validUrl(String);
  public NetHandle request(NetRequestOptions, Consumer<NetResult>);
  public NetHandle get(String, Consumer<NetResult>);
  public NetHandle postJson(String, String, Consumer<NetResult>);
  public NetHandle get(String, Consumer<HttpResponse>, Consumer<Throwable>);
  public NetHandle send(Net$HttpRequest, Consumer<HttpResponse>, Consumer<Throwable>);
  public boolean configureMonitor(String, double, int);
  public void checkNow();
  public NetSnapshot snapshot();
  public boolean isOnline();
  public long subscribe(Consumer<NetSnapshot>);
  public boolean unsubscribe(long);
  public int pendingCount();
  public int size();
  public boolean setMaxConcurrent(int);
  public int maxConcurrent();
  public void cancelAll();
  public void update();
  public boolean isClosed();
  public void shutdown();
  public void dispose();
}
```

### HttpResponse

包：`com.meowody.runtime.net`。源码：[HttpResponse.java](../core/src/main/java/com/meowody/runtime/net/HttpResponse.java)。

```java
public final class HttpResponse {
  public final int status;
  public final String body;
}
```

### NetError

包：`com.meowody.runtime.net`。源码：[NetError.java](../core/src/main/java/com/meowody/runtime/net/NetError.java)。

```java
public final class NetError extends Enum<NetError> {
  public static final NetError NONE;
  public static final NetError NOT_READY;
  public static final NetError INVALID_ARGUMENT;
  public static final NetError TRANSPORT;
  public static final NetError TIMEOUT;
  public static final NetError HTTP_STATUS;
  public static final NetError TOO_LARGE;
  public static final NetError CANCELLED;
  public static final NetError CLOSED;
  public static NetError[] values();
  public static NetError valueOf(String);
}
```

### NetHandle

包：`com.meowody.runtime.net`。源码：[NetHandle.java](../core/src/main/java/com/meowody/runtime/net/NetHandle.java)。

```java
public final class NetHandle {
  public NetHandle();
  public NetRequestStatus status();
  public NetResult result();
  public boolean isDone();
  public boolean isFinished();
  public double progressPercent();
  public synchronized void cancel();
}
```

### NetRequestOptions

包：`com.meowody.runtime.net`。源码：[NetRequestOptions.java](../core/src/main/java/com/meowody/runtime/net/NetRequestOptions.java)。

```java
public final class NetRequestOptions {
  public String url;
  public String method;
  public Map<String, String> headers;
  public byte[] body;
  public double timeoutSeconds;
  public long maxResponseBytes;
  public NetRequestOptions();
  public NetRequestOptions copy();
}
```

源码字段及初始化值：

```java
public String url;
public String method = "GET";
public Map<String, String> headers = new LinkedHashMap<>();
public byte[] body = new byte[0];
public double timeoutSeconds = 15;
public long maxResponseBytes = 8 * 1024 * 1024;
```

### NetRequestStatus

包：`com.meowody.runtime.net`。源码：[NetRequestStatus.java](../core/src/main/java/com/meowody/runtime/net/NetRequestStatus.java)。

```java
public final class NetRequestStatus extends Enum<NetRequestStatus> {
  public static final NetRequestStatus PENDING;
  public static final NetRequestStatus RUNNING;
  public static final NetRequestStatus SUCCEEDED;
  public static final NetRequestStatus FAILED;
  public static final NetRequestStatus CANCELLED;
  public static NetRequestStatus[] values();
  public static NetRequestStatus valueOf(String);
}
```

### NetResult

包：`com.meowody.runtime.net`。源码：[NetResult.java](../core/src/main/java/com/meowody/runtime/net/NetResult.java)。

```java
public final class NetResult extends Record {
  public NetResult(NetError, int, Map<String, List<String>>, byte[], String);
  public boolean ok();
  public String text();
  public NetError error();
  public int statusCode();
  public Map<String, List<String>> headers();
  public byte[] body();
  public String message();
}
```

### NetSnapshot

包：`com.meowody.runtime.net`。源码：[NetSnapshot.java](../core/src/main/java/com/meowody/runtime/net/NetSnapshot.java)。

```java
public final class NetSnapshot extends Record {
  public NetSnapshot(NetStatus, boolean, int, double, double, String);
  public NetStatus status();
  public boolean hasInterface();
  public int consecutiveFailures();
  public double lastCheckSeconds();
  public double latencyMs();
  public String probeUrl();
}
```

### NetStatus

包：`com.meowody.runtime.net`。源码：[NetStatus.java](../core/src/main/java/com/meowody/runtime/net/NetStatus.java)。

```java
public final class NetStatus extends Enum<NetStatus> {
  public static final NetStatus UNKNOWN;
  public static final NetStatus NO_INTERFACE;
  public static final NetStatus LOCAL_ONLY;
  public static final NetStatus CHECKING;
  public static final NetStatus ONLINE;
  public static final NetStatus UNREACHABLE;
  public static NetStatus[] values();
  public static NetStatus valueOf(String);
}
```

### DownloadCenter

包：`com.meowody.runtime.download`。源码：[DownloadCenter.java](../core/src/main/java/com/meowody/runtime/download/DownloadCenter.java)。

```java
public final class DownloadCenter implements Disposable {
  public DownloadCenter();
  public DownloadCenter(FileHandle);
  public static String hash(byte[]);
  public static String digest(Path) throws Exception;
  public DownloadHandle download(String, DownloadOptions, Consumer<DownloadResult>);
  public DownloadHandle download(String, Consumer<DownloadResult>);
  public DownloadHandle download(String, FileHandle, DownloadOptions, Consumer<DownloadResult>);
  public DownloadHandle download(String, FileHandle, Consumer<FileHandle>, Consumer<Throwable>);
  public String getCachedPath(String, DownloadOptions);
  public boolean hasCached(String, DownloadOptions);
  public boolean removeCached(String, DownloadOptions);
  public DownloadHandle getHandle(String, DownloadOptions);
  public DownloadProgress query(String, DownloadOptions);
  public DownloadResult getResult(String, DownloadOptions);
  public float progressPercent(String, DownloadOptions);
  public boolean isFinished(String, DownloadOptions);
  public boolean pause(String, DownloadOptions);
  public boolean resume(String, DownloadOptions);
  public boolean cancel(String, DownloadOptions);
  public void cancelAll();
  public int pendingCount();
  public int size();
  public boolean isIdle();
  public boolean isClosed();
  public String cacheDirectory();
  public int maxConcurrent();
  public void setMaxConcurrent(int);
  public void update();
  public void shutdown();
  public void dispose();
}
```

### DownloadError

包：`com.meowody.runtime.download`。源码：[DownloadError.java](../core/src/main/java/com/meowody/runtime/download/DownloadError.java)。

```java
public final class DownloadError extends Enum<DownloadError> {
  public static final DownloadError NONE;
  public static final DownloadError INVALID_URL;
  public static final DownloadError INVALID_OPTIONS;
  public static final DownloadError BUSY;
  public static final DownloadError NETWORK;
  public static final DownloadError TIMEOUT;
  public static final DownloadError HTTP;
  public static final DownloadError PROTOCOL;
  public static final DownloadError FILE_IO;
  public static final DownloadError TOO_LARGE;
  public static final DownloadError CHECKSUM;
  public static final DownloadError CANCELLED;
  public static final DownloadError CLOSED;
  public static final DownloadError INVALID_HANDLE;
  public static DownloadError[] values();
  public static DownloadError valueOf(String);
}
```

### DownloadHandle

包：`com.meowody.runtime.download`。源码：[DownloadHandle.java](../core/src/main/java/com/meowody/runtime/download/DownloadHandle.java)。

```java
public final class DownloadHandle {
  public DownloadHandle();
  public long bytes();
  public long total();
  public int retries();
  public DownloadStatus status();
  public DownloadResult result();
  public float progress();
  public float progressPercent();
  public boolean isDone();
  public boolean isFinished();
  public boolean succeeded();
  public boolean failed();
  public boolean cancelled();
  public boolean paused();
  public boolean sizeKnown();
  public synchronized void pause();
  public synchronized void resume();
  public synchronized void cancel();
}
```

### DownloadOptions

包：`com.meowody.runtime.download`。源码：[DownloadOptions.java](../core/src/main/java/com/meowody/runtime/download/DownloadOptions.java)。

```java
public final class DownloadOptions {
  public boolean resume;
  public boolean useCache;
  public int maxRetries;
  public int maxRedirects;
  public double timeoutSeconds;
  public double retryDelaySeconds;
  public long maxBytes;
  public String expectedSha256;
  public Map<String, String> headers;
  public DownloadOptions();
  public DownloadOptions copy();
}
```

源码字段及初始化值：

```java
public boolean resume = true, useCache = true;
public int maxRetries = 2, maxRedirects = 5;
public double timeoutSeconds = 30, retryDelaySeconds = 1;
public long maxBytes = 512L * 1024 * 1024;
public String expectedSha256 = "";
public Map<String, String> headers = new LinkedHashMap<>();
```

### DownloadProgress

包：`com.meowody.runtime.download`。源码：[DownloadProgress.java](../core/src/main/java/com/meowody/runtime/download/DownloadProgress.java)。

```java
public final class DownloadProgress extends Record {
  public DownloadProgress(boolean, DownloadStatus, long, long, int, DownloadResult);
  public boolean finished();
  public float progressPercent();
  public boolean exists();
  public DownloadStatus status();
  public long downloadedBytes();
  public long totalBytes();
  public int retries();
  public DownloadResult result();
}
```

### DownloadResult

包：`com.meowody.runtime.download`。源码：[DownloadResult.java](../core/src/main/java/com/meowody/runtime/download/DownloadResult.java)。

```java
public final class DownloadResult extends Record {
  public DownloadResult(String, DownloadError, int, String, boolean);
  public boolean ok();
  public String path();
  public DownloadError error();
  public int httpStatus();
  public String message();
  public boolean fromCache();
}
```

### DownloadStatus

包：`com.meowody.runtime.download`。源码：[DownloadStatus.java](../core/src/main/java/com/meowody/runtime/download/DownloadStatus.java)。

```java
public final class DownloadStatus extends Enum<DownloadStatus> {
  public static final DownloadStatus QUEUED;
  public static final DownloadStatus CONNECTING;
  public static final DownloadStatus DOWNLOADING;
  public static final DownloadStatus RETRYING;
  public static final DownloadStatus PAUSED;
  public static final DownloadStatus SUCCEEDED;
  public static final DownloadStatus FAILED;
  public static final DownloadStatus CANCELLED;
  public static DownloadStatus[] values();
  public static DownloadStatus valueOf(String);
}
```

### SaveCenter

包：`com.meowody.runtime.save`。源码：[SaveCenter.java](../core/src/main/java/com/meowody/runtime/save/SaveCenter.java)。

```java
public final class SaveCenter implements Disposable {
  public SaveCenter(String);
  public SaveCenter(String, FileHandle);
  public SaveCenter(FileHandle, SaveOptions);
  public SaveStatus configure(FileHandle, SaveOptions);
  public String partPath(String);
  public String directory();
  public SaveStatus save(String, String, Object);
  public <T> SaveResult<T> load(String, String, Class<T>, T);
  public SaveResult<Map<String, Object>> inspect(String, boolean);
  public boolean hasSlot(String, String);
  public Set<String> listSlots(String);
  public Set<String> listParts();
  public SaveStatus eraseSlot(String, String);
  public SaveStatus resetPart(String);
  public boolean isPending(String);
  public int pendingCount();
  public long commitCount();
  public SaveStatus lastFlushStatus();
  public void setFlushScheduler(Runnable);
  public void requestFlush();
  public SaveStatus flushPending();
  public SaveStatus flushFrame(long);
  public SaveStatus saveObject(String, String, Object);
  public SaveStatus loadInto(String, String, Object);
  public SaveStatus saveModel(String, String, SaveModel);
  public ModelLoadResult loadModel(String, String, SaveModel);
  public <T> void save(String, T);
  public <T> T load(String, Class<T>, T);
  public boolean contains(String);
  public Set<String> slots();
  public void delete(String);
  public boolean isClosed();
  public SaveStatus shutdown();
  public void dispose();
}
```

### ModelLoadResult

包：`com.meowody.runtime.save`。源码：[ModelLoadResult.java](../core/src/main/java/com/meowody/runtime/save/ModelLoadResult.java)。

```java
public final class ModelLoadResult extends Record {
  public ModelLoadResult(SaveError, String, int, int, boolean);
  public boolean ok();
  public SaveError error();
  public String message();
  public int savedVersion();
  public int currentVersion();
  public boolean migrated();
}
```

### SaveError

包：`com.meowody.runtime.save`。源码：[SaveError.java](../core/src/main/java/com/meowody/runtime/save/SaveError.java)。

```java
public final class SaveError extends Enum<SaveError> {
  public static final SaveError NONE;
  public static final SaveError INVALID_ARGUMENT;
  public static final SaveError MISSING;
  public static final SaveError CORRUPT;
  public static final SaveError IO;
  public static final SaveError INVALID_KEY;
  public static final SaveError UNSUPPORTED_DATA;
  public static final SaveError CLOSED;
  public static final SaveError UNSUPPORTED_VERSION;
  public static final SaveError MIGRATION_FAILED;
  public static SaveError[] values();
  public static SaveError valueOf(String);
}
```

### SaveModel

包：`com.meowody.runtime.save`。源码：[SaveModel.java](../core/src/main/java/com/meowody/runtime/save/SaveModel.java)。

```java
public interface SaveModel {
  public abstract int saveVersion();
  public default SaveStatus migrateSave(int, Map<String, Object>);
}
```

### SaveOptions

包：`com.meowody.runtime.save`。源码：[SaveOptions.java](../core/src/main/java/com/meowody/runtime/save/SaveOptions.java)。

```java
public final class SaveOptions {
  public boolean deferWrites;
  public byte[] encryptionKey;
  public long maxFileBytes;
  public SaveOptions();
  public SaveOptions copy();
}
```

源码字段及初始化值：

```java
public boolean deferWrites = true;
public byte[] encryptionKey = new byte[0];
public long maxFileBytes = 64L * 1024 * 1024;
```

### SaveResult

包：`com.meowody.runtime.save`。源码：[SaveResult.java](../core/src/main/java/com/meowody/runtime/save/SaveResult.java)。

```java
public final class SaveResult<T> extends Record {
  public SaveResult(SaveError, String, T, boolean);
  public boolean ok();
  public SaveError error();
  public String message();
  public T data();
  public boolean usedDefault();
}
```

### SaveStatus

包：`com.meowody.runtime.save`。源码：[SaveStatus.java](../core/src/main/java/com/meowody/runtime/save/SaveStatus.java)。

```java
public final class SaveStatus extends Record {
  public SaveStatus(SaveError, String);
  public boolean ok();
  public SaveError error();
  public String message();
}
```

### TimeCenter

包：`com.meowody.runtime.time`。源码：[TimeCenter.java](../core/src/main/java/com/meowody/runtime/time/TimeCenter.java)。

```java
public final class TimeCenter implements Disposable {
  public TimeCenter();
  public TimeCenter(DoubleSupplier, DoubleSupplier);
  public double systemUnixTime();
  public double monotonicSeconds();
  public boolean networkSynced();
  public double networkSampleAge();
  public TimeReading now();
  public TimeReading now(TimeSource);
  public DateTime toDateTime(double, ZoneId);
  public DateTime dateTime(ZoneId, TimeSource);
  public String format(ZoneId, TimeSource);
  public String format();
  public TimeSyncHandle syncNetwork(String, Consumer<TimeSyncResult>);
  public TimeSyncHandle syncNetwork(NtpOptions, Consumer<TimeSyncResult>);
  public boolean isSyncing();
  public void cancelSync();
  public DailySubscription subscribeDaily(DailyOptions, Consumer<DailyEvent>);
  public DailySubscription daily(int, int, Runnable, ZoneId);
  public boolean unsubscribe(long);
  public boolean pause(long);
  public boolean resume(long);
  public int subscriptionCount();
  public void clearSubscriptions();
  public void update();
  public void update(float);
  public void setScale(float);
  public float scale();
  public float delta();
  public float realDelta();
  public double elapsed();
  public double realElapsed();
  public boolean isClosed();
  public void shutdown();
  public void dispose();
}
```

### DailyEvent

包：`com.meowody.runtime.time`。源码：[DailyEvent.java](../core/src/main/java/com/meowody/runtime/time/DailyEvent.java)。

```java
public final class DailyEvent extends Record {
  public DailyEvent(long, DateTime, double, boolean);
  public long subscriptionId();
  public DateTime dateTime();
  public double unixSeconds();
  public boolean fromNetwork();
}
```

### DailyOptions

包：`com.meowody.runtime.time`。源码：[DailyOptions.java](../core/src/main/java/com/meowody/runtime/time/DailyOptions.java)。

```java
public final class DailyOptions {
  public int hour;
  public int minute;
  public int second;
  public ZoneId zone;
  public TimeSource source;
  public boolean catchUpToday;
  public DailyOptions();
  public DailyOptions copy();
}
```

源码字段及初始化值：

```java
public int hour, minute, second;
public ZoneId zone = ZoneId.systemDefault();
public TimeSource source = TimeSource.BEST_AVAILABLE;
public boolean catchUpToday;
```

### DailySubscription

包：`com.meowody.runtime.time`。源码：[DailySubscription.java](../core/src/main/java/com/meowody/runtime/time/DailySubscription.java)。

```java
public final class DailySubscription implements AutoCloseable {
  public long id();
  public boolean isActive();
  public boolean isPaused();
  public void cancel();
  public void close();
  public void pause();
  public void resume();
}
```

### DateTime

包：`com.meowody.runtime.time`。源码：[DateTime.java](../core/src/main/java/com/meowody/runtime/time/DateTime.java)。

```java
public final class DateTime extends Record {
  public DateTime(boolean, ZonedDateTime);
  public int dateKey();
  public int secondsOfDay();
  public String iso8601();
  public int utcOffsetSeconds();
  public boolean daylightSaving();
  public boolean valid();
  public ZonedDateTime value();
}
```

### NtpOptions

包：`com.meowody.runtime.time`。源码：[NtpOptions.java](../core/src/main/java/com/meowody/runtime/time/NtpOptions.java)。

```java
public final class NtpOptions {
  public String server;
  public int port;
  public double timeoutSeconds;
  public double maxRoundTripSeconds;
  public NtpOptions();
}
```

源码字段及初始化值：

```java
public String server;
public int port = 123;
public double timeoutSeconds = 5;
public double maxRoundTripSeconds = 3;
```

### NtpProtocol

包：`com.meowody.runtime.time`。源码：[NtpProtocol.java](../core/src/main/java/com/meowody/runtime/time/NtpProtocol.java)。

```java
public final class NtpProtocol {
  public static void writeTimestamp(byte[], int, double);
  public static TimeSyncResult decode(byte[], byte[], double, double, double, String);
}
```

### TimeReading

包：`com.meowody.runtime.time`。源码：[TimeReading.java](../core/src/main/java/com/meowody/runtime/time/TimeReading.java)。

```java
public final class TimeReading extends Record {
  public TimeReading(boolean, boolean, double);
  public boolean available();
  public boolean fromNetwork();
  public double unixSeconds();
}
```

### TimeSource

包：`com.meowody.runtime.time`。源码：[TimeSource.java](../core/src/main/java/com/meowody/runtime/time/TimeSource.java)。

```java
public final class TimeSource extends Enum<TimeSource> {
  public static final TimeSource SYSTEM;
  public static final TimeSource NETWORK;
  public static final TimeSource BEST_AVAILABLE;
  public static TimeSource[] values();
  public static TimeSource valueOf(String);
}
```

### TimeSyncError

包：`com.meowody.runtime.time`。源码：[TimeSyncError.java](../core/src/main/java/com/meowody/runtime/time/TimeSyncError.java)。

```java
public final class TimeSyncError extends Enum<TimeSyncError> {
  public static final TimeSyncError NONE;
  public static final TimeSyncError NOT_READY;
  public static final TimeSyncError INVALID_ARGUMENT;
  public static final TimeSyncError BUSY;
  public static final TimeSyncError RATE_LIMITED;
  public static final TimeSyncError DNS;
  public static final TimeSyncError TRANSPORT;
  public static final TimeSyncError TIMEOUT;
  public static final TimeSyncError INVALID_RESPONSE;
  public static final TimeSyncError SERVER_REJECTED;
  public static final TimeSyncError CANCELLED;
  public static final TimeSyncError CLOSED;
  public static TimeSyncError[] values();
  public static TimeSyncError valueOf(String);
}
```

### TimeSyncHandle

包：`com.meowody.runtime.time`。源码：[TimeSyncHandle.java](../core/src/main/java/com/meowody/runtime/time/TimeSyncHandle.java)。

```java
public final class TimeSyncHandle {
  public TimeSyncHandle();
  public TimeSyncStatus status();
  public boolean isFinished();
  public boolean succeeded();
  public TimeSyncResult result();
  public synchronized void cancel();
}
```

### TimeSyncResult

包：`com.meowody.runtime.time`。源码：[TimeSyncResult.java](../core/src/main/java/com/meowody/runtime/time/TimeSyncResult.java)。

```java
public final class TimeSyncResult extends Record {
  public TimeSyncResult(TimeSyncError, String, String, double, double, double);
  public boolean ok();
  public TimeSyncError error();
  public String message();
  public String server();
  public double unixSeconds();
  public double offsetSeconds();
  public double roundTripSeconds();
}
```

### TimeSyncStatus

包：`com.meowody.runtime.time`。源码：[TimeSyncStatus.java](../core/src/main/java/com/meowody/runtime/time/TimeSyncStatus.java)。

```java
public final class TimeSyncStatus extends Enum<TimeSyncStatus> {
  public static final TimeSyncStatus PENDING;
  public static final TimeSyncStatus RESOLVING;
  public static final TimeSyncStatus WAITING;
  public static final TimeSyncStatus SUCCEEDED;
  public static final TimeSyncStatus FAILED;
  public static final TimeSyncStatus CANCELLED;
  public static TimeSyncStatus[] values();
  public static TimeSyncStatus valueOf(String);
}
```

### LogCenter

包：`com.meowody.runtime.log`。源码：[LogCenter.java](../core/src/main/java/com/meowody/runtime/log/LogCenter.java)。

```java
public final class LogCenter {
  public static void setMinimum(LogLevel);
  public static synchronized void setLimits(int, int, int);
  public static synchronized LogSubscription subscribe(Consumer<LogRecord>);
  public static void trace(String, String, Object...);
  public static void debug(String, String, Object...);
  public static void info(String, String, Object...);
  public static void warn(String, String, Object...);
  public static void error(String, Throwable, String, Object...);
  public static void error(String, String, Object...);
  public static synchronized List<LogRecord> records();
  public static synchronized List<String> history();
  public static synchronized List<LogRecord> filter(int, String, String);
  public static synchronized long revision();
  public static synchronized long dropped();
  public static synchronized void clear();
}
```

### LogLevel

包：`com.meowody.runtime.log`。源码：[LogLevel.java](../core/src/main/java/com/meowody/runtime/log/LogLevel.java)。

```java
public final class LogLevel extends Enum<LogLevel> {
  public static final LogLevel TRACE;
  public static final LogLevel DEBUG;
  public static final LogLevel INFO;
  public static final LogLevel WARN;
  public static final LogLevel ERROR;
  public static LogLevel[] values();
  public static LogLevel valueOf(String);
}
```

### LogRecord

包：`com.meowody.runtime.log`。源码：[LogRecord.java](../core/src/main/java/com/meowody/runtime/log/LogRecord.java)。

```java
public final class LogRecord extends Record {
  public LogRecord(long, long, LogLevel, String, String, String, String, Throwable);
  public String formatted();
  public long sequence();
  public long timeMillis();
  public LogLevel level();
  public String tag();
  public String text();
  public String source();
  public String thread();
  public Throwable error();
}
```

### LogSubscription

包：`com.meowody.runtime.log`。源码：[LogSubscription.java](../core/src/main/java/com/meowody/runtime/log/LogSubscription.java)。

```java
public final class LogSubscription implements AutoCloseable {
  public boolean isConnected();
  public void close();
}
```

### DebugCenter

包：`com.meowody.runtime.debug`。源码：[DebugCenter.java](../core/src/main/java/com/meowody/runtime/debug/DebugCenter.java)。

```java
public final class DebugCenter implements Disposable {
  public int toggleKey;
  public DebugCenter();
  public void registerPanel(String, Supplier<String>);
  public boolean registerPanel(String, String, Supplier<Actor>);
  public boolean unregisterPanel(String);
  public void setEnabled(boolean);
  public boolean isEnabled();
  public boolean isOpen();
  public boolean capturesInput();
  public void open();
  public void close();
  public void toggle();
  public void setOpen(boolean);
  public boolean registerCommand(DebugCommand);
  public boolean unregisterCommand(String);
  public boolean executeCommand(String);
  public static List<String> parseCommand(String);
  public List<String> complete(String);
  public void print(String, boolean);
  public List<String> history();
  public List<String> output();
  public List<PerformanceSample> samples();
  public String stats();
  public String exportSave(String, String, boolean, boolean);
  public String exportLogs();
  public void setSceneRoot(Actor);
  public InputProcessor input();
  public void update(double);
  public void render(float);
  public BaseStage stage();
  public void resize(int, int);
  public boolean isClosed();
  public void shutdown();
  public void dispose();
}
```

### DebugCommand

包：`com.meowody.runtime.debug`。源码：[DebugCommand.java](../core/src/main/java/com/meowody/runtime/debug/DebugCommand.java)。

```java
public final class DebugCommand extends Record {
  public DebugCommand(String, String, String, Function<DebugCommandContext, DebugCommandResult>, Function<DebugCommandContext, List<String>>);
  public String name();
  public String description();
  public String usage();
  public Function<DebugCommandContext, DebugCommandResult> execute();
  public Function<DebugCommandContext, List<String>> complete();
}
```

### DebugCommandContext

包：`com.meowody.runtime.debug`。源码：[DebugCommandContext.java](../core/src/main/java/com/meowody/runtime/debug/DebugCommandContext.java)。

```java
public final class DebugCommandContext extends Record {
  public DebugCommandContext(String, List<String>);
  public String line();
  public List<String> args();
}
```

### DebugCommandResult

包：`com.meowody.runtime.debug`。源码：[DebugCommandResult.java](../core/src/main/java/com/meowody/runtime/debug/DebugCommandResult.java)。

```java
public final class DebugCommandResult extends Record {
  public DebugCommandResult(boolean, String);
  public static DebugCommandResult success(String);
  public static DebugCommandResult failure(String);
  public boolean ok();
  public String text();
}
```

### PerformanceSample

包：`com.meowody.runtime.debug`。源码：[PerformanceSample.java](../core/src/main/java/com/meowody/runtime/debug/PerformanceSample.java)。

```java
public final class PerformanceSample extends Record {
  public PerformanceSample(double, int, float, long);
  public double seconds();
  public int fps();
  public float frameMs();
  public long javaHeap();
}
```

### ManagerUIEditor

包：`com.meowody.runtime.cocos`。源码：[ManagerUIEditor.java](../core/src/main/java/com/meowody/runtime/cocos/ManagerUIEditor.java)。

```java
public class ManagerUIEditor extends BaseCocoStudioUIEditor implements Disposable {
  public ManagerUIEditor(FileHandle, String, AssetManager, BitmapFont);
  public ManagerUIEditor(CCExport, String, AssetManager, BitmapFont);
  public <T extends ObjectData> Drawable findDrawable(T, FileData);
  public TextureRegion findTextureRegion(ObjectData, FileData);
  public BitmapFont createLabelStyleBitmapFint(ObjectData, String, Color);
  public TTFLabelStyle createLabelStyle(ObjectData, String, Color);
  public BitmapFont findBitmapFont(FileData);
  public BaseCocoStudioUIEditor findCoco(FileData);
  public String findParticePath(FileData);
  public void dispose();
}
```

### BaseCocoStudioUIEditor

包：`com.meowody.runtime.cocos`。源码：[BaseCocoStudioUIEditor.java](../core/src/main/java/com/meowody/runtime/cocos/BaseCocoStudioUIEditor.java)。

```java
public abstract class BaseCocoStudioUIEditor {
  protected CCExport export;
  protected Map<Actor, Action> actorActionMap;
  protected Map<String, Array<Actor>> actors;
  protected Map<Integer, Actor> actionActors;
  protected Map<String, BaseWidgetParser> parsers;
  public void own(Disposable);
  public void disposeGroup(Group);
  public void disposeOwned();
  public BaseCocoStudioUIEditor(FileHandle);
  public BaseCocoStudioUIEditor(CCExport);
  public abstract <T extends ObjectData> Drawable findDrawable(T, FileData);
  public abstract BitmapFont createLabelStyleBitmapFint(ObjectData, String, Color);
  public abstract BaseCocoStudioUIEditor findCoco(FileData);
  public Actor parseWidget(Group, ObjectData);
  public Group createGroup();
  public abstract TTFLabelStyle createLabelStyle(ObjectData, String, Color);
  public abstract BitmapFont findBitmapFont(FileData);
  public abstract TextureRegion findTextureRegion(ObjectData, FileData);
  public abstract String findParticePath(FileData);
  public void addParser(BaseWidgetParser);
}
```

### ManagerUILoader

包：`com.meowody.runtime.cocos.loader`。源码：[ManagerUILoader.java](../core/src/main/java/com/meowody/runtime/cocos/loader/ManagerUILoader.java)。

```java
public final class ManagerUILoader extends SynchronousAssetLoader<ManagerUIEditor, ManagerUIParameters> {
  public ManagerUILoader(FileHandleResolver);
  public Array<AssetDescriptor> getDependencies(String, FileHandle, ManagerUIParameters);
  public ManagerUIEditor load(AssetManager, String, FileHandle, ManagerUIParameters);
  public Object load(AssetManager, String, FileHandle, AssetLoaderParameters);
  public Array getDependencies(String, FileHandle, AssetLoaderParameters);
}
```

### ManagerUIParameters

包：`com.meowody.runtime.cocos.loader`。源码：[ManagerUIParameters.java](../core/src/main/java/com/meowody/runtime/cocos/loader/ManagerUIParameters.java)。

```java
public final class ManagerUIParameters extends AssetLoaderParameters<ManagerUIEditor> {
  public final String assetRoot;
  public String packEntry;
  public final List<String> ancestors;
  public ManagerUIParameters(String);
  public String resolveRoot(FileHandle);
}
```

### CocosUiLoader

包：`com.meowody.runtime.cocos.loader`。源码：[CocosUiLoader.java](../core/src/main/java/com/meowody/runtime/cocos/loader/CocosUiLoader.java)。

```java
public final class CocosUiLoader extends AsynchronousAssetLoader<CCExport, CocosLayoutParameters> {
  public static final String DEFAULT_FONT;
  public CocosUiLoader(FileHandleResolver);
  public static String metadataKey(String);
  public Array<AssetDescriptor> getDependencies(String, FileHandle, CocosLayoutParameters);
  public void loadAsync(AssetManager, String, FileHandle, CocosLayoutParameters);
  public CCExport loadSync(AssetManager, String, FileHandle, CocosLayoutParameters);
  public Object loadSync(AssetManager, String, FileHandle, AssetLoaderParameters);
  public void loadAsync(AssetManager, String, FileHandle, AssetLoaderParameters);
  public Array getDependencies(String, FileHandle, AssetLoaderParameters);
}
```

### CocosLayoutParameters

包：`com.meowody.runtime.cocos.loader`。源码：[CocosLayoutParameters.java](../core/src/main/java/com/meowody/runtime/cocos/loader/CocosLayoutParameters.java)。

```java
public final class CocosLayoutParameters extends AssetLoaderParameters<CCExport> {
  public final String assetRoot;
  public final List<String> ancestors;
  public CocosLayoutParameters(String);
}
```

### PlistAtlas

包：`com.meowody.runtime.cocos.plist`。源码：[PlistAtlas.java](../core/src/main/java/com/meowody/runtime/cocos/plist/PlistAtlas.java)。

```java
public class PlistAtlas implements Disposable {
  public PlistAtlas();
  public PlistAtlas(String);
  public PlistAtlas(FileHandle);
  public PlistAtlas(FileHandle, FileHandle);
  public PlistAtlas(PlistAtlas$PlistAtlasData);
  public TextureAtlas$AtlasRegion addRegion(String, Texture, int, int, int, int);
  public TextureAtlas$AtlasRegion addRegion(String, TextureRegion);
  public Array<TextureAtlas$AtlasRegion> getRegions();
  public TextureAtlas$AtlasRegion findRegion(String);
  public TextureAtlas$AtlasRegion findRegion(String, int);
  public Array<TextureAtlas$AtlasRegion> findRegions(String);
  public Array<Sprite> createSprites();
  public Sprite createSprite(String);
  public Sprite createSprite(String, int);
  public Array<Sprite> createSprites(String);
  public NinePatch createPatch(String);
  public ObjectSet<Texture> getTextures();
  public void dispose();
}
```

### PlistAtlasLoader

包：`com.meowody.runtime.cocos.plist`。源码：[PlistAtlasLoader.java](../core/src/main/java/com/meowody/runtime/cocos/plist/PlistAtlasLoader.java)。

```java
public class PlistAtlasLoader extends SynchronousAssetLoader<PlistAtlas, PlistAtlasParameter> {
  public PlistAtlasLoader(FileHandleResolver);
  public PlistAtlas load(AssetManager, String, FileHandle, PlistAtlasParameter);
  public Array<AssetDescriptor> getDependencies(String, FileHandle, PlistAtlasParameter);
  public Object load(AssetManager, String, FileHandle, AssetLoaderParameters);
  public Array getDependencies(String, FileHandle, AssetLoaderParameters);
}
```

### CCExport

包：`com.meowody.runtime.cocos.model`。源码：[CCExport.java](../core/src/main/java/com/meowody/runtime/cocos/model/CCExport.java)。

```java
public class CCExport {
  public String ID;
  public String Version;
  public String Name;
  public CCData Content;
  public String Type;
  public CCExport();
}
```
