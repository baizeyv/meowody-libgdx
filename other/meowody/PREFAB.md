# BasePrefab

继承 `com.meowody.runtime.prefab.BasePrefab`，调用构造方法加载 CocosStudio
JSON；`App` 需已初始化。每次实例化创建独立节点树，资源通过 ResourceCenter
复用，释放实例时归还资源引用。

```java
public final class DicePrefab extends BasePrefab {
    private final Actor face;

    public DicePrefab() {
        super("prefabs/Dice.json", "ui");
        face = require("Face", Actor.class);
        onClick("Face", this::roll);
    }

    public void roll() {
        face.addAction(Actions.rotateBy(360, .4f));
    }

    @Override
    protected void onDispose() {
        // 释放子类独占的资源；不要释放由布局加载器管理的纹理。
    }
}

DicePrefab dice = new DicePrefab();
parent.addActor(dice);
dice.setPosition(100, 200);
// 不再使用时：
dice.dispose();
```

`super(jsonPath)` 默认以 JSON 所在目录为资源根目录；第二个参数可指定
资源根目录，规则与 BaseUI 相同。在子类构造方法的 `super(...)` 之后绑定
节点和设置初始逻辑，基类不会在构造过程中调用子类初始化钩子。

BasePrefab 自身就是 JSON 根节点，没有额外 Group 包装。根属性、回调、
Action 和子节点直接解析到实例；`root()` 返回 `this`。提供 `require()`、
`onClick()`、`listen()`、`track()`；`setSize()` 直接改变布局根尺寸，
继承的 `act()` 支持 Action 和布局更新。
JSON 使用 `SingleNodeObjectData` 或 `LayerObjectData` 作为根，Panel、
ScrollView 等专用控件作为子节点，保留其自身绘制与滚动行为。
`dispose()` 幂等，调用 `onDispose()`，取消托管协程、退订事件、移除节点并
释放布局和资源引用。`remove()` 仅从场景移除，不销毁，可用于暂存或对象池。
手动创建的嵌套 prefab 由创建方负责在 `onDispose()` 中调用其 `dispose()`。

BaseUI 继承 BasePrefab，共用上述能力，继续保留原有页面和缓存生命周期。
检查命令：`gradlew.bat :meowody:prefabCheck :meowody:layoutCheck`。

## 提前加载

```java
// 加载阶段：持续运行正常的 App.update()，完成后再创建实例。
LoadHandle<ManagerUIEditor> preload = App.resource().preloadPrefab(
    "prefabs/Dice.json", "ui", result -> {
        if (!result.ok()) {
            LogCenter.error("Prefab", result.error, "Dice preload failed");
            return;
        }
        parent.addActor(new DicePrefab());
    });

// 离开使用该预制体的场景时，先 dispose 实例，再释放预加载句柄。
preload.close();
```

预加载复用 ManagerUILoader / CocosUiLoader，自动加载 JSON 声明的纹理、
图集、字体及嵌套 ProjectNode JSON。预加载和 BasePrefab 构造方法应使用
相同路径与 assetRoot；句柄未释放期间，LRU 淘汰不会卸载其模板和依赖。
创建实例仍需在主线程构建 Actor、绑定事件与业务逻辑，不是零耗时。
未等待预加载成功时，构造方法仍会同步加载资源。
`preloadPrefab` 是 Cocos JSON 专用入口，普通 JSON 不会被自动识别为布局。
