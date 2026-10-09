# Meowody 框架

参考 `D:/Develop/GDCPP/meow` 的中心职责和生命周期，Cocos parser 移植自 plane1945，保留 Apache 2.0 声明。详细使用步骤见 [框架使用教程](docs/FRAMEWORK_TUTORIAL.md)，MVP 实战见 [MVP 架构教程](docs/MVP_TUTORIAL.md)，精确签名及参数说明见 [API 手册](docs/API_REFERENCE.md)。功能对照及平台差异见 [CENTER_PARITY.md](CENTER_PARITY.md)。没有增加运行时依赖。

## 运行与验证

使用 Java 17：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE/.jdks/ms-17.0.20.1"
./gradlew.bat :lwjgl3:run
./gradlew.bat :core:frameworkTest :lwjgl3:frameworkSmokeTest :android:compileDebugJavaWithJavac
```

首页演示 Cocos 页面、弹窗、事件、存档和延迟任务。FPS 旁的 Debug 按钮或 F12/F3 打开运行时详情，Escape 返回。Android 已配置 INTERNET 权限，编译已验证；设备运行和真实音频仍需实机验证。

## 目录与入口

代码位于 `core/src/main/java/com/meowody/runtime`，按 `audio/coroutine/download/event/net/pool/resource/save/time/ui/debug/log/cocos/base` 分目录，公开类型独立成文件。原 `net.mwplay.cocostudio.ui` 和 `com.ui` 已迁入 `com.meowody.runtime.cocos`。

统一渲染配置位于 `ProjectConfig.java`：1080×1920 标准竖屏、ExtendViewport、桌面窗口540×960。createViewport/createBatch 首次创建，之后返回共享单例；默认 Screen、UI、Debug 共用，App 退出时统一释放 Batch。需要独立游戏相机时使用 BaseScreen(Viewport)。

`Meowody extends BaseGame` 创建首个 Screen。`BaseGame` 初始化 App、更新中心、绘制 Screen/UI/Debug、每帧合并存档写入并在退出时释放。替换 Screen 会释放旧 Screen。`BaseScreen implements Screen`、`BaseStage extends Stage`、`BaseUI`、`BasePopup` 的构造都无需 App 参数。

```java
App.audio(); App.coroutine(); App.download(); App.event(); App.net();
App.pool(); App.resource(); App.save(); App.time(); App.ui(); App.debug();
LogCenter.info("Battle", "score=%d", score);
```

App 只公开 static 方法，不能 new App、传 App 实例或访问公开实例字段。LogCenter 是全局 static，不属于 App，可以在 App 初始化前使用。其他中心应在 Gdx 和 App 初始化后访问。中心 API 默认由渲染线程调用，网络、下载、资源和 NTP 的后台结果在 update 时回到渲染线程。跨线程修改 UI 请通过 `Gdx.app.postRunnable`。

默认桌面持久化目录为用户目录下 `.meowody/<namespace>`，Android 使用应用本地目录；下载、存档、资源包和 Debug 导出分目录保存。可覆盖 BaseGame.storageDirectory()，不向 assets 写运行数据。

## Cocos UI 与 AssetManager

ResourceCenter 继承 AssetManager，初始化时注册：

```java
assetManager.setLoader(CCExport.class, new CocosUiLoader(resolver));
assetManager.setLoader(PlistAtlas.class, new PlistAtlasLoader(resolver));
assetManager.setLoader(ManagerUIEditor.class, new ManagerUILoader(resolver));
```

ManagerUILoader 声明布局元数据依赖，布局加载器收集纹理、字体、plist 和嵌套布局依赖。资源管理器缓存 ManagerUIEditor 模板，每个 BaseUI 创建独立 Group，关闭时释放自身节点资源和资源引用。

```java
public final class BattleUI extends BaseUI {
    public BattleUI() {
        super("ui/battle.json", "ui");
        onClick("BackButton", () -> App.ui().back());
    }
    @Override public void onOpen(Object data) {
        // 绑定本次打开的数据；listen/track 的订阅与任务在关闭时清理。
    }
}
```

BaseUI extends Group，保留 Cocos 节点坐标和层级，不依赖 Skin，也不直接 new ManagerUIEditor。UICenter 内的 Skin 供 代码创建的通用控件使用；Debug 使用独立字体样式。默认 assetRoot 为布局目录；若导出资源引用基于其他目录，使用第二个构造参数或 UIOptions.assetRoot。

```java
UIOptions options = new UIOptions();
options.kind = UIKind.PAGE; // WINDOW、MODAL、OVERLAY
App.ui().configure("ui/battle.json", options, BattleUI::new, null);
UIHandle handle = App.ui().open("ui/battle.json", battleData);
App.ui().close(handle.id());
```

可配置多实例、持久界面、缓存和 Action 入退场动画。页面叠加时暂停前页，关闭后恢复；模态弹窗屏蔽下层输入并恢复焦点。异步打开回调通过 update 派发。缓存重新打开会复用节点树，但产生新 handle 和新一轮生命周期。UIPresenter 可管理界面逻辑及其订阅。

## 中心使用约定

- AudioCenter：默认 Music/SFX/UI/Voice 通道，音量、静音、暂停、淡入淡出、并发上限及抢占、播放句柄和完成回调；逻辑音频总线参与音量计算。按要求无 pitch 控制。
- CoroutineCenter：`Coroutine.make().then(...).yield(Coroutine.waitForSeconds(...))`，也支持帧数、条件和嵌套协程；异常隔离，句柄可取消。
- EventCenter：按事件 Class 分发，返回可关闭订阅；派发期间移除立即生效，新订阅下次生效。
- NetCenter：HTTP 请求、响应限额、超时、取消；网络接口状态与服务探测分开。状态和结果订阅在 update 派发。
- DownloadCenter：流式下载、并发上限、暂停/恢复、Range 断点续传、重试、校验、缓存和原子替换；取消后不调用完成回调。
- PoolCenter：Java 对象和 Actor 工厂池，预热、容量、统计、Poolable 生命周期；Actor 回收会脱离父节点并清理根节点 Action。
- ResourceCenter：类型化同步/异步加载、去重、引用租约、资源组和 LRU；支持远程资源和 ZIP 资源包。`LoadResult<T>` 是 AutoCloseable，使用完应 close；资源组释放其持有的引用。
- SaveCenter：分区/槽、帧内合并、校验和、原子落盘、可选 AES-GCM、对象字段及 SaveModel 版本迁移。查询可区分待写值与磁盘值；显式 flush 用于立即持久化，失败可从结果查询。
- TimeCenter：游戏缩放时间、系统/单调时间、UTC/本地日期、显式 NTP 同步及每日调度；网络时间只在同步成功后有效。
- LogCenter：统一格式、五级日志、受限历史、过滤和订阅。
- DebugCenter：FPS HUD、性能采样、日志、存档、节点树、中心统计、自定义面板以及带历史/补全的命令控制台。

## 验证范围

core 测试覆盖中心生命周期和重要边界（含本地 HTTP 服务、下载暂停续传/校验、损坏和加密存档、资源引用、NTP 报文）；桌面测试使用真实 OpenGL，覆盖 Cocos、UI 缓存/模态/动画/Presenter、Debug 控制台及切屏清理。两个 Cocos UI 共用模板但拥有独立节点树的场景也有验证。

Android 验证为 Java 编译。公共 NTP 服务联通、远程 ZIP 完整流程、真实音频播放以及所有旧 Cocos 特殊控件和动画轨道尚未逐项实测，不能由上述测试推断全部可用。
