# 框架使用教程

本文按当前 Java 实现编写。精确签名、Options 字段、状态与错误枚举见 [API_REFERENCE.md](API_REFERENCE.md)；平台移植差异见 [CENTER_PARITY.md](../CENTER_PARITY.md)。文中的音频、业务布局和服务 URL 是接入示例，需要替换为项目实际资源。项目内可直接运行的示例是 `com.meowody.runtime.FirstScreen`。

完整 MVP 示例见 [MVP 架构使用教程](MVP_TUTORIAL.md)，包含金币 Model、Cocos View、Presenter、Screen 注册和缓存重开清理。

## 阅读顺序

1. 先阅读配置、生命周期和 Cocos UI，建立第一个 Screen/页面。
2. 再接入事件、存档、协程和音频。
3. 按业务需要接入对象池、网络、下载、资源组和时间调度。
4. 最后使用 Debug 观察状态，并查 API 手册处理各类结果。

## 1. 项目结构与启动

```text
core/src/main/java/com/meowody/runtime/
  App.java                 静态入口与中心生命周期
  ProjectConfig.java       标准尺寸、Viewport、Batch、桌面窗口尺寸
  Meowody.java             游戏入口示例
  FirstScreen.java         Cocos 页面/弹窗示例
  base/                    BaseGame、BaseScreen、BaseStage
  ui/                      BaseUI、BasePopup、UICenter、Presenter
  cocos/                   编辑器数据、parser、加载器、plist
  audio/ coroutine/ event/ net/ download/
  pool/ resource/ save/ time/ log/ debug/
assets/ui/                 可运行的 home.json、popup.json 和纹理
lwjgl3/                    桌面 Launcher 与真实 GL 测试
android/                   Android Launcher 与 Manifest
```

业务代码可以另建 `com.meowody.game.screen`、`game.ui`、`game.model` 等目录。框架中心放 runtime，业务不需要继承 App 或持有 App 对象。

使用 Java 17 启动桌面：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE/.jdks/ms-17.0.20.1"
./gradlew.bat :lwjgl3:run
```

完整检查：

```powershell
./gradlew.bat :core:frameworkTest :lwjgl3:frameworkSmokeTest :android:compileDebugJavaWithJavac
```

`core:check` 也会运行 frameworkTest。桌面冒烟测试打开隐藏的真实 OpenGL 窗口，不播放真实音频，生成 `build/framework-smoke.png` 和 `build/framework-debug-smoke.png`。

## 2. 标准分辨率、Viewport 和 Batch

统一修改 `ProjectConfig.java`：

```java
public static float standardWidth = 1080;
public static float standardHeight = 1920;
public static int desktopWidth = 540;
public static int desktopHeight = 960;
public static Supplier<Viewport> viewportFactory =
        () -> new ExtendViewport(standardWidth, standardHeight);
public static Supplier<Batch> batchFactory = SpriteBatch::new;
```

标准尺寸是逻辑坐标尺寸。桌面默认 540×960，相当于设计尺寸的一半；手机使用实际设备像素。Android Manifest 已配置 portrait。桌面窗口尺寸不影响世界坐标中的 1080×1920。

`createViewport()` 和 `createBatch()` **只在第一次调用时创建对象，之后返回同一实例**。这些方法保留 create 命名，但实际承担懒加载访问器的职责。修改 factory 必须在首次访问之前进行，不会替换已经创建的对象。

所有默认 BaseStage（包括 Screen、UICenter、DebugCenter）共用这个 Viewport 和 Batch；不需要为每个 UI 配置渲染对象。BaseStage.dispose() 只释放 Stage 自己的内容，不释放共享 Batch。App.shutdown() 在所有 Stage 退出后调用 ProjectConfig.dispose()，统一释放 Batch 并清空两个缓存，下次初始化可重新创建。

### ExtendViewport 如何适配

- 9:16 屏幕：世界尺寸为 1080×1920。
- 比 9:16 更宽：保持最低高度，扩展横向世界范围。
- 比 9:16 更长：保持最低宽度，扩展纵向世界范围。
- 实际 worldWidth/worldHeight 可能因屏幕像素取整略有偏差，不要用理论计算值做严格浮点相等判断。

定位 HUD、屏幕边缘、遮罩时，应读取 `stage.getWidth()/getHeight()` 或 Viewport.worldWidth/worldHeight；`Gdx.graphics.getWidth()/getHeight()` 是窗口/设备像素，用于 resize 和屏幕坐标转换。

```java
float worldWidth = stage.getWidth();
float worldHeight = stage.getHeight();
actor.setPosition(worldWidth - actor.getWidth(), worldHeight - actor.getHeight());
```

Cocos 页面仍保留其导出尺寸，UICenter 居中并等比适配，窗口/弹窗不会因空余空间而被放大。建议编辑器画布同样设置 1080×1920。页面如何铺满扩展区域需要业务布局处理，ExtendViewport 本身不会重排 Cocos 节点。

### 共享相机与独立世界

Viewport 包含 Camera。默认共享也就共享相机；改变其位置、缩放或投影会影响 UI 和 Debug。游戏世界需要相机移动时，给游戏世界单独的 Viewport：

```java
BaseStage worldStage = new BaseStage(new ExtendViewport(1080, 1920));
// 默认仍借用共享 Batch，但拥有独立 Camera。
```

BaseScreen 的默认 stage 是共享视口。需要替换它时，使用 `BaseScreen(Viewport)` 构造；UI 和 Debug 继续使用默认共享视口。

如确实需要显式指定 Batch：

```java
Batch customBatch = new SpriteBatch();
BaseStage specialStage = new BaseStage(new ExtendViewport(1080, 1920), customBatch);
// specialStage.dispose() 后，由创建者调用 customBatch.dispose()。
```

同一个 Batch 只能顺序绘制。BaseGame 顺序绘制 Screen → UI → Debug；不要在一次 begin/end 内再调用 Stage.draw()，也不要同时在多个线程使用它。

## 3. App 静态入口与生命周期

```java
App.audio();      // AudioCenter
App.coroutine();  // CoroutineCenter
App.download();   // DownloadCenter
App.event();      // EventCenter
App.net();        // NetCenter
App.pool();       // PoolCenter
App.resource();   // ResourceCenter / AssetManager
App.save();       // SaveCenter
App.time();       // TimeCenter
App.ui();         // UICenter
App.debug();      // DebugCenter
App.storageDirectory();
```

所有对外 App 方法都是 static，构造器和实例字段都是 private，不再有公共 App.get()。不要传 App 参数或把 App 保存在业务对象中。LogCenter 独立于 App。

BaseGame 自动完成初始化/更新/释放。正常项目不要再次调用 App.initialize/update/shutdown，否则会重复初始化或重复更新。若自行实现 ApplicationListener，才手动接管这些 API，同时必须绘制 UI/Debug、处理 resize/input 和存档 flush。

`App.isInitialized()` 可以在初始化前查询。其他中心访问器在未初始化/已退出时抛 IllegalStateException。重复 initialize 会拒绝操作并保留当前 App。

每帧顺序：

```text
Time.update
  → Net.update → Download.update → Resource.update
  → Coroutine.update → Audio.update
  → Screen.render(缩放 delta)
  → UI.render(真实 delta)
  → Debug.render(真实 delta)
  → Save.flushFrame(递增帧号)
```

暂停时间缩放不影响 UI、Debug、HTTP/下载等后台结果的派发。应用 pause/resume 会暂停/恢复音频，但保留各通道/声音自己的暂停状态。

### 线程规则

中心管理、加载资源、修改 UI 和事件派发默认在渲染线程进行。HTTP、下载和 NTP 在后台工作，完成回调通过 update 派发；不要阻塞渲染线程等待异步句柄完成。

Event.emit 是同步调用；LogCenter 订阅也在写日志的线程立即调用。如果工作线程要通知界面：

```java
Gdx.app.postRunnable(() -> App.event().emit(new ScoreChanged(100)));
```

### 持久化目录

桌面默认位于用户目录 `.meowody/<namespace>`；Android 使用应用本地 `meowody/<namespace>`。目录包含 downloads、saves、resource-packs、debug_exports。assets 用于只读发布资源。

可以在游戏入口覆盖 storageDirectory()。不要把线上存档路径指向 packaged assets。

## 4. BaseGame、BaseScreen、BaseStage

最小入口：

```java
public final class MyGame extends BaseGame {
    @Override protected Screen createFirstScreen() {
        return new MenuScreen();
    }
    @Override protected String saveNamespace() {
        return "my-game";
    }
}
```

Screen 示例：

```java
public final class MenuScreen extends BaseScreen {
    @Override public void show() {
        UIOptions options = new UIOptions();
        options.kind = UIKind.PAGE;
        App.ui().configure("ui/menu.json", options, MenuUI::new, null);
        App.ui().open("ui/menu.json");
        listen(ScoreChanged.class, event -> LogCenter.info("Menu", "score=%d", event.score()));
        track(App.coroutine().delay(1, false, () -> LogCenter.info("Menu", "ready")));
    }
}
```

默认 BaseScreen.render 会 act/draw 默认 Stage；覆盖 render/resize/hide/dispose 时，应调用对应 super 方法。BaseScreen.listen/track 持有的事件订阅和协程在 hide/dispose 时清理。

`BaseGame.setScreen(next)` 先关闭非持久 UI，再调用 Screen.hide/show，并释放 previous。旧 Screen 不能重复使用；返回某场景时创建新实例。persistent UI 会保留。setScreen 会重绑输入处理器并更新 Debug 的场景树入口。

BaseStage.resize(width,height) 更新 Viewport 并把相机居中；参数是像素尺寸。零尺寸 resize 会忽略。共享 Viewport 时不要在各层存放互相矛盾的相机状态。

## 5. Cocos 布局资源流程

支持的是 Cocos Studio 导出的 JSON 布局与现有 parser 能识别的类型，不是任意 Cocos Creator 场景格式。

```text
JSON → ManagerUILoader → CCExport 布局依赖
                      → CocosUiLoader 收集纹理/字体/plist/嵌套布局
                      → ManagerUIEditor 模板
                      → createGroup() 创建独立节点树
```

ResourceCenter 已注册三个加载器，无需业务再次 setLoader：

```java
CCExport.class        → CocosUiLoader
PlistAtlas.class      → PlistAtlasLoader
ManagerUIEditor.class → ManagerUILoader
```

同一文件名不能同时作为不同类型交给 AssetManager。框架内部使用独立元数据 key，避免 ManagerUIEditor 与 CCExport 的冲突。业务统一使用 BaseUI 或 ManagerUIEditor 类型加载，不要将同一布局路径额外当 FileHandle 加载。

资源根目录应与导出 JSON 中的引用一致。示例 JSON 在 `assets/ui/home.json`，图片引用为 `pixel.png`，因此传 `ui`。如果引用为 `ui/pixel.png`，根目录应指向 assets 根。根目录错误通常表现为依赖文件不存在。

布局节点名字是运行时绑定协议。建议编辑器里给需要绑定的按钮/标签稳定命名，并通过 require(name,type) 检查类型。

## 6. BaseUI 和 BasePopup

```java
public final class MenuUI extends BaseUI {
    private final Label score;
    public MenuUI() {
        super("ui/menu.json", "ui");
        score = require("ScoreLabel", Label.class);
        onClick("StartButton", () -> App.ui().open("ui/battle.json"));
    }
    @Override public void onOpen(Object data) {
        listen(ScoreChanged.class, event -> score.setText("Score: " + event.score()));
    }
}
```

BaseUI extends Group，无 Skin/App 参数，不自己 new ManagerUIEditor。空构造可用于代码创建 UI，再用 setRoot(Group) 设置根节点。

- root() 返回 parser 创建的 Group。
- require(name,type) 递归查节点，不存在或类型不符就抛 IllegalArgumentException。
- onClick(name,action) 绑定 ClickListener。
- listen/track 将订阅/任务归属于本次打开周期。
- dispose 释放自身订阅、任务、节点生成资源和布局租约；不会释放另一个 UI 的节点树。

缓存 UI 的构造函数只执行一次；每次重新打开会再次 onOpen。可重复的事件订阅应放 onOpen，不要放构造函数。点击监听是节点的永久绑定，可放构造函数，避免每次打开重复添加。

弹窗：

```java
public final class ConfirmPopup extends BasePopup {
    public ConfirmPopup() {
        super("ui/confirm.json", "ui");
        onClick("CancelButton", this::close);
    }
}
App.ui().openPopup(new ConfirmPopup());
```

BasePopup.close() 通过 UICenter 关闭弹窗。普通 BaseUI 请通过 UIHandle 或 App.ui().close(path/id) 关闭，不要绕过 UICenter 直接 dispose 已挂载的界面。

## 7. UICenter 管理页面、窗口和模态

### 注册与打开

```java
UIOptions options = new UIOptions();
options.kind = UIKind.PAGE;
options.assetRoot = "ui";
App.ui().configure("ui/menu.json", options, MenuUI::new, null);
UIHandle menu = App.ui().open("ui/menu.json", initialData);
```

不提供 factory 时，UICenter 根据路径创建 Cocos BaseUI。factory 适合绑定业务节点的 UI 子类。没有布局的代码界面也可以使用稳定逻辑路径，例如 `code/inventory`，并提供 factory。

| 类型 | 行为 |
| --- | --- |
| PAGE | 页面叠加时暂停前页，关闭顶页后恢复前页 |
| WINDOW | 一般窗口，默认允许下层继续处理未消费输入 |
| MODAL | 带遮罩，屏蔽下层输入并保存/恢复焦点 |
| OVERLAY | 覆盖层；Actor 是否接收输入仍由节点 Touchable 决定 |

默认 kind=WINDOW、multiple=false、persistent=false、cache=true、dismissOnBack=true、动画超时30秒。Options 在 configure 时复制，之后修改原对象不会重新配置已经注册的界面。

### Handle、关闭与缓存

```java
App.ui().find("ui/menu.json");
App.ui().get(menu.id());
menu.bringToFront();
App.ui().close(menu.id());
App.ui().back();
App.ui().closeAll(false); // 保留 persistent 界面
```

UIHandle.close/bringToFront 是请求，通常在下一次 UI.update 处理；App.ui().close 会直接进入关闭流程。需要读当前结果时检查 status()。不要根据 isFinished() 判断“界面已销毁”：OPEN/SUSPENDED 也意味着打开操作完成。

缓存关闭后旧 handle.root() 变 null；重新打开可以复用原 BaseUI，但产生新 id。关闭 handle 后不要长期持有它的 root。clearCache 释放已关闭缓存，不关闭当前活动 UI。默认容量16，可 setCacheCapacity。

multiple=false 时重复打开会复用活动 handle 并再次发送 data。multiple=true 时每次创建独立 handle。persistent=true 的界面跨 Screen 切换保留，适合全局通知或下载面板。

### 异步打开与资源包

```java
UIHandle pending = App.ui().openAsync("ui/menu.json", initialData, handle -> {
    if (handle.isOpen()) LogCenter.info("UI", "opened id=%d", handle.id());
    else LogCenter.warn("UI", "failed: %s", handle.error());
});
pending.cancel(); // 仅排队/加载阶段可取消；取消后不执行完成回调
```

异步回调不在 openAsync 的调用栈立即执行。即使 factory 无加载工作，也通过 update 处理。UIOptions.packUrl 可指定 ZIP URL，path 是包内布局路径，assetRoot 按包内资源基准填写。ZIP 必须包含布局的依赖文件。

### 入场、退场和生命周期

```java
options.enterAnimation = () -> Actions.sequence(
        Actions.alpha(0), Actions.fadeIn(.2f));
options.exitAnimation = () -> Actions.fadeOut(.15f);
options.animationTimeout = 3;
```

每次通过 Supplier 创建新的 Action，不要复用正由另一个 Actor 执行的 Action。生命周期为 onOpen → 入场 → onOpened；关闭时 onClosing → 退场 → onClose。覆盖页面触发 onSuspend，重新显示触发 onResume。超时用于避免动画永不结束阻塞生命周期。

### Presenter 分离业务逻辑

配置 Presenter 后，UICenter 将同名生命周期回调交给 Presenter，**不会再自动调用 View 的对应回调**。需要更新 View 时，由 Presenter 调用 View 的显式方法。完整接入步骤见 [MVP 教程](MVP_TUTORIAL.md)。

```java
public final class MenuPresenter extends UIPresenter {
    private MenuUI view;
    @Override public void onAttach(BaseUI root) { view = (MenuUI) root; }
    @Override public void onOpen(Object data) {
        track(App.event().on(ScoreChanged.class,
                event -> LogCenter.info("Menu", "score=%d", event.score())));
    }
}
App.ui().configure("ui/menu.json", options, MenuUI::new, MenuPresenter::new);
```

Presenter 每轮生命周期管理自身连接，通过 track(AutoCloseable)/defer(Runnable) 逆序清理。缓存的是 View；不要假设 Presenter 和 View 一样长期存在。协程句柄不是 AutoCloseable，可 `defer(handle::cancel)`。

## 8. EventCenter：事件解耦

```java
public record ScoreChanged(int score) { }
EventSubscription subscription = App.event().on(ScoreChanged.class, event -> {
    LogCenter.info("Score", "%d", event.score());
});
App.event().emit(new ScoreChanged(120));
subscription.close();
```

按精确 Class 分发，不会自动分发给父类或接口订阅。派发同步执行；回调中移除尚未执行的订阅会阻止它本轮执行，新订阅下轮生效。无参回调可以 connect(Class,Runnable)。emit(Class) 需要可用的无参构造，record 示例应传对象。

clear(Class) 清空该类型订阅，保留类型通道；clearAll/clear 清空全部。Screen/UI 中优先用基类 listen，避免忘记 close。

## 9. CoroutineCenter：帧协程

```java
CoroutineHandle task = App.coroutine().start(Coroutine.make()
        .then(() -> LogCenter.info("Wave", "start"))
        .yield(Coroutine.waitForSeconds(1))
        .then(() -> LogCenter.info("Wave", "spawn"))
        .yield(Coroutine.waitForFrames(2))
        .then(() -> LogCenter.info("Wave", "finish")));
task.cancel();
```

这是步骤链，不是 Java 线程或 Kotlin coroutine。then 立即步骤在同一帧连续执行，直到某个 step 返回 false；yield 首次执行先让出一帧，即使等待0秒/0帧也是如此。

自定义任务 `start(delta -> { ...; return finished; }, unscaled)`：true 表示完成，false 下帧继续。unscaled=false 使用游戏缩放时间，true 使用真实时间。链式 start(Coroutine) 默认游戏时间；真实时间链可使用 `start(coroutine, true)`。

```java
track(App.coroutine().delay(2, true, this::showNotice));
Coroutine nested = Coroutine.make().yield(Coroutine.waitUntil(() -> loaded));
App.coroutine().start(Coroutine.make().yield(nested).then(this::beginBattle));
```

条件必须快速返回，不要在其中阻塞。取消只停止后续步骤，不自动撤销已执行操作。异常会记录日志并结束该任务，其他任务继续。

## 10. AudioCenter：声音与通道

快捷 API：

```java
App.audio().playMusic("audio/menu.ogg", true);
App.audio().playSound("audio/click.ogg");
App.audio().setMusicVolume(.6f);
App.audio().setSoundVolume(.8f);
```

默认通道 Music、SFX、UI、Voice。需要控制单个声音时：

```java
AudioPlayOptions play = new AudioPlayOptions();
play.volume = .8f;
play.looping = false;
AudioHandle voice = App.audio().play("Voice", "audio/intro.ogg", play,
        () -> LogCenter.info("Audio", "natural completion"));
voice.pause(); voice.resume();
voice.seek(1.5f);
voice.setVolume(.5f);
voice.stop();
```

完成回调只表示自然播放结束，手动 stop/抢占/失败不会调用。先检查 status/isFinished 再继续控制。音量为有限0..1，淡化时间为秒。

```java
App.audio().fadeVolume("Music", .2f, .5);
App.audio().setPaused("Voice", true);
App.audio().setMuted("SFX", true);
App.audio().createBus("Effects");
AudioChannelOptions channel = new AudioChannelOptions();
channel.bus = "Effects"; channel.maxVoices = 8; channel.stealOldest = true;
App.audio().createChannel("Battle", channel);
App.audio().setBusVolume("Effects", .7f);
```

实际音量由全局、逻辑总线、通道和声音音量叠乘，静音使结果为0。总线必须先创建。默认 maxVoices16，抢占最早声音；不抢占时超额播放会失败。逻辑总线无 DSP 效果器，按要求不提供 pitch。

默认使用独立 Music 流，支持 seek/播放状态；真实可用格式和同时播放数量由平台音频实现决定。setPlayerFactory/直接传 Supplier<AudioPlayer> 可接自定义播放器，停止后由中心释放播放器。

## 11. PoolCenter：普通对象与预制节点池

```java
public final class BulletData implements Poolable {
    public float x, y;
    public void onPoolAcquire() { x = y = 0; }
    public void onPoolRelease() { x = y = 0; }
}
PoolOptions options = new PoolOptions();
options.prewarm = 32; options.maxIdle = 128; options.maxTotal = 256;
App.pool().create("bullet-data", BulletData.class, BulletData::new, options);
BulletData data = App.pool().acquireAs("bullet-data", BulletData.class);
if (data != null) App.pool().release(data);
```

maxTotal=0 表示不限总数，maxIdle 限制闲置数量；达到总上限时 acquire 可能返回 null。池维护对象身份，只能释放属于本中心的活动对象；重复回收不会重复入池。

```java
App.pool().registerPrefab("bullet-node", Group.class, Group::new,
        node -> node.setPosition(0, 0), 128);
Group bullet = (Group) App.pool().acquire("bullet-node", stage.getRoot());
App.pool().free(bullet);
```

预制体在这里是返回 Actor 的工厂，可从已经加载好的 Cocos 模板创建 Group。回收会 remove() 并清除根节点 Action；子节点监听、子节点 Action 和业务状态仍由 reset/Poolable 负责。不要每次回收 dispose 节点使用的共享纹理。

prewarm 目标是闲置数量；stats 可查询 idle/active/capacity。discard 强制丢弃，remove(name,destroyActive) 明确决定如何处理活动对象。remove(false) 转移活动对象所有权，之后调用方自己释放；shutdown 销毁池管理的 Disposable 对象。

## 12. ResourceCenter：资源引用、分组与远程加载

### 同步租约

```java
LoadResult<Texture> lease = App.resource().loadResult("textures/player.png", Texture.class);
if (!lease.ok()) throw new GdxRuntimeException("load failed", lease.error);
Texture texture = lease.resource;
// 保留 lease，直到所有使用 texture 的 Actor 都停止使用。
lease.close();
```

租约 close 是释放引用，不必立即销毁纹理，缓存或其他租约仍可保持资源有效。不要直接 texture.dispose()。暂时使用可 try-with-resources；跨帧显示的资源不要在创建 Actor 后立即关闭租约。

loadNow 使用传统 AssetManager 引用，需对应 unload(path)。`load/get/unload` 和 `loadResult/close` 是两套所有权方式，选择一套并配对，避免混用导致额外引用或过早释放。

### 异步加载

```java
LoadHandle<Texture> request = App.resource().loadAsync("textures/player.png", Texture.class, result -> {
    if (result.ok()) {
        // 保存 result，并在场景退出后 close。
    } else {
        LogCenter.error("Resource", result.error, "Cannot load player");
    }
});
request.close(); // 未完成则取消；完成后释放这个请求的租约
```

同步 loadResult 不接受远程 URL；远程普通文件通过 loadAsync(URL,type,callback)。资源类型由调用者提供；不要假设 URL 扩展名存在。一个 key 同一时间只能对应一种资源类型。同名加载去重，但每个成功请求持有自己的引用。

### 场景资源组

```java
Map<String, Class<?>> assets = new LinkedHashMap<>();
assets.put("textures/player.png", Texture.class);
assets.put("ui/battle.json", ManagerUIEditor.class);
BatchLoadHandle group = App.resource().preloadGroup("battle", assets, done -> {
    if (done.succeeded()) LogCenter.info("Resource", "battle ready");
});
// 场景彻底退出时：
App.resource().releaseGroup("battle");
```

组持有成员租约。releaseGroup 释放组，不会绕过其他租约直接销毁仍使用中的资产。preloadPackGroup/loadPackAsync 支持 ZIP 包内条目；解压目录按包内容散列隔离，拒绝目录穿越等非法条目。

LRU 控制中心自己的缓存基础引用，默认引用仍可能被活动 UI/租约/组持有；clearLru 并不保证所有资源立刻从 AssetManager 消失。query/progressPercent 以0..100表示进度，失败和取消也属于完成状态，必须检查 status/result。

## 13. NetCenter：请求与联通状态

```java
NetHandle request = App.net().get("https://example.com/api/status", result -> {
    if (result.ok()) LogCenter.info("Net", "%s", result.text());
    else LogCenter.warn("Net", "error=%s http=%d %s",
            result.error(), result.statusCode(), result.message());
});
```

自定义请求：

```java
NetRequestOptions options = new NetRequestOptions();
options.url = "https://example.com/api/score";
options.method = "POST";
options.headers.put("Content-Type", "application/json");
options.body = "{\"score\":100}".getBytes(StandardCharsets.UTF_8);
options.timeoutSeconds = 10;
options.maxResponseBytes = 1024 * 1024;
App.net().request(options, result -> LogCenter.info("Net", "ok=%s", result.ok()));
```

默认请求超时15秒、响应限额8MiB、并发8。postJson 自动设置 JSON 请求。HTTP 非成功状态通过 NetError.HTTP_STATUS 返回，仍可检查 statusCode/body。text() 按 UTF-8 解码；二进制使用 body()。

cancel 会抑制未派发完成回调。不要只依赖“网络在线”才能请求；在线状态是某个探测 URL 的结果，并不保证目标业务接口可用。

```java
App.net().configureMonitor("https://example.com/health", 30, 3);
long listener = App.net().subscribe(snapshot -> LogCenter.info("Network", "%s", snapshot.status()));
App.net().checkNow();
App.net().unsubscribe(listener);
```

状态区别：UNKNOWN 未确认，NO_INTERFACE 无接口，LOCAL_ONLY 有接口但未配置有效服务探测，CHECKING 检查中，ONLINE 服务探测成功，UNREACHABLE 达到失败阈值。Android 已配置 INTERNET；如使用明文 HTTP，需按目标 SDK 配置 Android 网络策略。

当前传输是 HttpURLConnection，标准桌面 PATCH 不可用，CONNECT 未提供。业务应使用当前传输支持的普通请求方法，或接入所需的传输实现。

## 14. DownloadCenter：文件下载

```java
DownloadOptions options = new DownloadOptions();
options.maxBytes = 64L * 1024 * 1024;
options.expectedSha256 = expectedHash;
DownloadHandle download = App.download().download(url, options, result -> {
    if (result.ok()) LogCenter.info("Download", "file=%s cached=%s", result.path(), result.fromCache());
    else LogCenter.warn("Download", "%s %s", result.error(), result.message());
});
```

下载到显式位置可用 `(url, FileHandle target, options, callback)` 重载。默认下载到缓存目录。失败不会替换原目标；下载和校验成功后才原子提交。

```java
download.pause();
download.resume();
float percent = download.progressPercent();
boolean known = download.sizeKnown();
long received = download.bytes();
download.cancel();
```

断点续传默认启用，依赖服务端 Range、长度及 ETag/Last-Modified 一致性；服务端返回完整200时重新下载。暂停释放并发槽。默认并发4、重试2、重定向5、超时30秒、重试间隔1秒、限额512MiB。

未知总长度时无法可靠显示百分比，可显示已收到字节。完成进度100%包括失败/取消，检查 succeeded/result.ok 判断成功。取消不执行完成回调，直接查询 handle.cancelled/status。

缓存依据 URL 和请求头区分，并校验侧文件信息；相同进行中的请求去重，不兼容 Options 会返回 BUSY。getCachedPath/hasCached/removeCached 使用与原请求相同的 URL 和 Options。业务自己指定的目标文件不应被当作中心默认缓存路径。

## 15. SaveCenter：存档、分区与迁移

### 基础分区与延迟写

```java
SaveStatus write = App.save().save("profile", "coins", 100);
SaveResult<Integer> read = App.save().load("profile", "coins", Integer.class, 0);
int coins = read.data();
if (!read.ok()) LogCenter.warn("Save", "%s default=%s", read.error(), read.usedDefault());
```

默认 deferWrites=true。save 返回“本次变更被接受”的结果，BaseGame 帧末合并写入；同分区同一帧最多一次落盘。load 可读到待写值，inspect(part,true) 读磁盘，inspect(part,false) 包含待写变更。

```java
SaveStatus flushed = App.save().flushPending();
if (!flushed.ok()) LogCenter.error("Save", "flush failed: %s", flushed.message());
```

需要立即持久化时显式 flush。退出会尝试 flush；可检查 lastFlushStatus。名称归一化为小写，不能含路径穿越或非法文件名字符。多个相互独立系统可用 profile/settings/progress 分区。

快捷 save(slot,value)/load(slot,type,default) 使用 default 分区，适合少量原型数据。delete/contains/slots 同样作用于 default 分区。

### 对象与版本模型

```java
public final class PlayerSave implements SaveModel {
    public int coins;
    public int bestScore;
    @Override public int saveVersion() { return 2; }
    @Override public SaveStatus migrateSave(int fromVersion, Map<String, Object> fields) {
        if (fromVersion == 1) {
            fields.putIfAbsent("bestScore", 0);
            return new SaveStatus(SaveError.NONE, "");
        }
        return new SaveStatus(SaveError.MIGRATION_FAILED, "unsupported version");
    }
}
PlayerSave model = new PlayerSave();
ModelLoadResult loaded = App.save().loadModel("profile", "player", model);
// 缺少存档时，保留 model 的默认字段。
App.save().saveModel("profile", "player", model);
```

每次 migrateSave 负责 fromVersion → fromVersion+1，中心逐步迁移到 saveVersion。比当前代码更新的版本会拒绝，避免旧代码覆盖新格式。迁移成功后需按结果决定何时 saveModel/flush 重新保存。

saveObject/loadInto 用于没有版本的字段快照。跳过 static/transient/final/synthetic 字段；恢复前先验证所有字段，再赋值，避免失败时留下部分修改。嵌套对象需预先初始化，不能依赖读取时自动实例化任意类。修改持久化类名会影响现有 class 校验。

支持普通标量、数组、列表、字符串键 Map 和 DTO，拒绝循环引用及非有限数字。不要存 Stage、Actor、Texture、网络句柄或中心对象。存档是业务状态，加载资源后再根据状态重建场景。

### 加密与错误保护

```java
SaveOptions options = new SaveOptions();
options.encryptionKey = key32Bytes; // 32 字节
SaveStatus configured = App.save().configure(App.storageDirectory().child("saves"), options);
```

configure 会检查中心状态和待写变更，接入时读取返回值，推荐在首个 Screen.show 前配置。未设置 key 时是校验保护的普通存档；设置 key 后使用 AES-256-GCM。默认单分区限额64MiB。

错误密钥/损坏存档不会自动覆盖。resetPart 是明确清空操作；需要保留原文件时应先导出。该文件格式与 Godot 原存档格式不同。

## 16. TimeCenter：游戏时间与日历时间

```java
App.time().setScale(.5f);
float gameDelta = App.time().delta();
float realDelta = App.time().realDelta();
double gameSeconds = App.time().elapsed();
double realSeconds = App.time().realElapsed();
```

游戏累计时间用于动画/模拟；日历时间用于日期和每日任务，二者不要混用。暂停 scale=0 不会暂停系统时钟或日历调度。

```java
TimeReading reading = App.time().now(TimeSource.BEST_AVAILABLE);
String utc = App.time().format(ZoneOffset.UTC, TimeSource.BEST_AVAILABLE);
```

SYSTEM 使用系统 Unix 时间，NETWORK 使用成功同步后的网络样本加单调时钟增量，BEST_AVAILABLE 优先 NETWORK，否则 SYSTEM。NETWORK 未同步时 reading.available() 为 false，先检查有效性。

### NTP 同步

```java
App.time().syncNetwork("your-ntp-host", result -> {
    if (result.ok()) LogCenter.info("Time", "offset=%f rtt=%f", result.offsetSeconds(), result.roundTripSeconds());
    else LogCenter.warn("Time", "%s %s", result.error(), result.message());
});
```

显式指定服务器，默认 UDP123，超时5秒，可用 NtpOptions 调整端口和最大往返时间。最多一个同步进行中；失败尝试之间有64秒间隔。已有成功样本时会复用有效样本而非反复请求。cancelSync/句柄.cancel 抑制回调。

### 每日任务

```java
DailyOptions daily = new DailyOptions();
daily.hour = 5; daily.zone = ZoneId.of("Asia/Shanghai");
daily.catchUpToday = true;
DailySubscription subscription = App.time().subscribeDaily(daily,
        event -> LogCenter.info("Daily", "reset at %s", event));
subscription.pause(); subscription.resume(); subscription.close();
```

catchUpToday=true 表示当天时间点已过且尚未执行时补执行。相同日期最多执行一次，时钟回拨不重复。暂停期间错过的日期不逐日补执行；每日奖励是否已经发放还应存在业务存档中，日历调度本身不是跨进程奖励账本。

## 17. LogCenter：统一日志

```java
LogCenter.info("Battle", "score=%d x=%.2f", score, x);
LogCenter.warn("Resource", "missing %s", path);
LogCenter.error("Net", error, "request failed: %s", url);
LogCenter.setMinimum(LogLevel.TRACE);
```

格式遵循 String.format/Locale.ROOT，含时间、级别、tag、来源和线程。默认最低级别 DEBUG，缓冲2000条/4MiB，单消息最多16Ki字符。setLimits 可调整；超过容量丢弃旧记录，dropped 可查询。

```java
LogSubscription observer = LogCenter.subscribe(record -> {
    // 可能在写日志的工作线程调用，更新 UI 请 postRunnable。
});
List<LogRecord> warnings = LogCenter.filter(24, "", "");
observer.close();
```

级别位掩码依次为 TRACE1/DEBUG2/INFO4/WARN8/ERROR16，31=全部，24=WARN+ERROR。tag 是大小写不敏感精确匹配，text 搜索消息或来源。不传 Throwable 时直接使用 error(tag,format,args)，避免 error(tag,null,...) 的重载歧义。

## 18. DebugCenter：竖屏运行时调试

Debug 与普通 UI 共用 ProjectConfig 的 1080×1920 ExtendViewport/Batch。详情面板按世界尺寸布局，字体使用独立 Debug 字体，避免修改 Cocos 共用字体。顶部常驻 FPS + Debug 按钮；F12/F3 切换，Escape 关闭。界面打开时拦截下层输入，暂停游戏后仍能操作。

```java
App.debug().open();
App.debug().close();
App.debug().setEnabled(false); // 隐藏 HUD/面板，停止命令处理
App.debug().toggleKey = Input.Keys.F10;
```

内置页面包括 Runtime、Resources、Pools、Audio、Network、Time、Saves、Nodes、Logs、Console、Performance。横向标签可滚动；内容也使用 ScrollPane。

自定义文本面板：

```java
App.debug().registerPanel("Battle", () -> "Enemies: " + enemyCount);
```

自定义 Actor 面板：

```java
App.debug().registerPanel("battle", "Battle", () -> new Group());
App.debug().unregisterPanel("battle");
```

工厂惰性创建，面板卸载/中心退出时释放实现 Disposable 的自定义根。文本查询应快速返回，避免在这里执行 HTTP 或磁盘阻塞操作。

### 命令控制台

```text
help
clear
fps
runtime.stats
time
pause
resume
timescale 0.5
audio.volume SFX 0.5
audio.mute SFX true
save.list
save.list profile
save.show profile player disk
save.export profile json --plaintext player
save.export profile raw
```

命令参数支持引号、空字符串和反斜杠转义；上下键历史，Tab 补全。executeCommand 排队，下次 update 执行。history/output 是副本，不可通过修改它们改变控制台内部状态。

```java
App.debug().registerCommand(new DebugCommand(
        "battle.count", "Enemy count", "battle.count",
        context -> DebugCommandResult.success("Enemies: " + enemyCount),
        context -> List.of()));
App.debug().executeCommand("battle.count");
```

DebugCommand 构造字段顺序为 name、description、usage、execute、complete。业务命令可 unregisterCommand，内置命令受保护。自定义 complete 接收 DebugCommandContext。

exportLogs/exportSave 输出到 app 根目录 debug_exports。exportSave(part,slot,disk,raw)：disk 决定磁盘或含待写视图；raw 导出整个分区原文件（包括加密），否则导出 JSON 明文视图。

## 19. 生命周期与释放速查

| 获得的对象 | 应由谁释放 | 时机 |
| --- | --- | --- |
| App 中心 | BaseGame/App | 游戏退出 |
| 默认 Viewport/Batch | ProjectConfig/App | 所有 Stage 退出后 |
| 显式传入的 Batch | 创建者 | 最后一个使用者退出后 |
| BaseScreen | BaseGame | setScreen 替换/退出 |
| 管理中的 BaseUI | UICenter | 关闭且不缓存/缓存淘汰/退出 |
| EventSubscription/LogSubscription/DailySubscription | 业务或基类 scope | 不再需要时 close |
| CoroutineHandle | 业务或基类 scope | 离开所属场景时 cancel |
| LoadResult/LoadHandle | 请求者或资源组 | 停止使用资源后 close |
| NetHandle/DownloadHandle/TimeSyncHandle | 请求者 | 不再需要结果时 cancel |
| 池对象 | PoolCenter | 正常 release；销毁由 discard/remove/shutdown 管理 |

不要单独 dispose App.resource() 或 App.ui() 后继续游戏，中心共同服务同一生命周期。

## 20. 常见问题与排查

- App 未初始化：不要在 static 字段初始化器、Launcher 构造或 Gdx 启动前调用中心访问器。
- 界面加载失败：检查导出格式、ctype、资源根目录、依赖文件与 require 的节点名字/类型。
- UI 越开订阅越多：在 onOpen 用 listen/Presenter.track；关闭动作通过 UICenter；永久 ClickListener 只绑定一次。
- 页面物理位置错误：不要把窗口像素直接当1080×1920坐标；读取实际 worldWidth/worldHeight，屏幕输入用 Stage/Viewport 坐标转换。
- 移动世界相机导致 HUD 移动：共享 Viewport 的结果，给世界单独 Viewport。
- Batch 已释放：Stage 借用默认 Batch，业务不应提前 dispose；手动 shutdown 也不能在绘制过程中执行。
- 暂停后任务没继续：游戏时间协程符合暂停语义；用 unscaled=true 执行真实时间任务。
- 下载100%却没有文件：进度完成包含失败和取消，读 DownloadResult.error/status。
- 回调不执行：检查是否已 cancel、中心是否继续 update、是否等待在渲染线程造成阻塞。
- 资源仍留在 AssetManager：检查资源组、UI 缓存、租约和 LRU 基础引用，不能只看 clearLru。
- 存档值已读到但文件没变化：默认延迟写，等待帧末或显式 flushPending；查看 lastFlushStatus。
- 字体/控件不支持：原 Cocos parser 是部分实现，动态 TTF/中文字体和全部时间线能力尚未补齐，不能只靠资源加载器解决。

## 21. 本次验证与实际边界

已验证纯静态 App 接口、同一 Batch/Viewport 实例、多次 Stage.dispose 不释放共享 Batch、App 退出仅释放一次、竖屏和宽屏 resize、输入映射、Cocos 模板独立节点树、UI 生命周期/缓存/模态/动画/Presenter、各中心关键边界、本地 HTTP/下载与 Android Java 编译。

Android 编译不等于设备运行验证；真实音频、公共 NTP 联通、远程 ZIP 完整流程和所有旧 Cocos 控件仍需按业务资源实测。框架对照采用职责和生命周期对应，不承诺 Godot/C++ 文件格式或 API 二进制兼容。
