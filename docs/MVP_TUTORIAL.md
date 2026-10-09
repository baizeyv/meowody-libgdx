# MVP 架构使用教程

本教程使用当前框架的 `BaseUI + UIPresenter + UICenter`，通过“金币面板”演示完整 MVP 接入。所有 App 中心均通过 static 方法访问，不传 App 实例。它是文档示例，尚未添加业务布局；实际运行前需导出 `assets/ui/wallet.json`。

关联文档：[框架使用教程](FRAMEWORK_TUTORIAL.md)、[API 手册](API_REFERENCE.md)。

## 1. Model、View、Presenter 分别做什么

| 层 | 本项目中对应的类 | 负责 | 本例 |
| --- | --- | --- | --- |
| Model | 普通 Java 业务类/SaveModel | 状态、业务规则、合法变更 | WalletModel 保存金币并增加金币 |
| View | BaseUI/BasePopup 子类 | Cocos 节点绑定、显示数据、转发点击 | WalletUI 显示余额与操作结果 |
| Presenter | UIPresenter 子类 | 接收用户意图、调用 Model/中心、更新 View | WalletPresenter 处理领取、存档和变化事件 |

View 不计算奖励、不直接请求业务接口、不自行持久化。Model 不依赖 Label、Actor 或 Stage。Presenter 知道 View 和 Model，调用 App.save/event/net 等中心完成协调。

这里不额外创建 View 接口、通用 Model 基类或自动绑定系统；一个具体界面的 Presenter 直接使用其 View。多个不同 View 确实需要共用同一逻辑时，再抽取接口。

```text
用户点击
  → View 转发动作
  → Presenter 检查并调用 Model
  → Model 更新业务状态
  → Presenter 请求存档/通知事件
  → Presenter 调用 View.render(...)
```

存档、HTTP、音频和事件中心提供服务，本身不等于业务 Model。钱包余额仍应由 WalletModel 管理，不能让 View、存档文件和网络回调各维护一份互相冲突的余额。

## 2. 示例目录和 Cocos 节点

每个公开类独立一个文件：

```text
core/src/main/java/com/meowody/game/
  model/WalletModel.java
  event/WalletChanged.java
  ui/wallet/WalletUI.java
  ui/wallet/WalletPresenter.java
  screen/WalletScreen.java
assets/ui/wallet.json
```

在 Cocos Studio 中建立1080×1920布局，导出到 `assets/ui/wallet.json`，准备以下节点：

| 节点名 | libGDX 类型 | 用途 |
| --- | --- | --- |
| GoldLabel | Label | 金币余额 |
| MessageLabel | Label | 操作结果/错误信息 |
| ClaimButton | Actor（可为按钮类型） | 点击增加10金币 |

图片路径以 `assets/ui` 为基准，因此 View 使用 `super("ui/wallet.json", "ui")`。如导出的资源基准不同，调整第二个参数。

“点击增加10金币”只是展示调用链，不包含每日领取、服务端授权等业务规则。真正的领取资格应由 Model 或服务端规则决定。

## 3. Model：只管理业务状态

`WalletModel.java`：

```java
package com.meowody.game.model;

public final class WalletModel {
    private int gold;

    public WalletModel(int initialGold) {
        gold = Math.max(0, initialGold);
    }

    public int gold() {
        return gold;
    }

    public void addGold(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        gold = Math.addExact(gold, amount);
    }
}
```

Model 不需要继承框架类，也不保存 App、View 或 Presenter。Math.addExact 避免整数溢出后出现负余额。更复杂的背包、任务、战斗状态同样可以使用普通 Java 类。

本例使用单个整数槽保存余额；需要保存多个字段和升级存档结构时，再采用 SaveModel，见框架教程第15节。业务 Model 与存档 DTO 可以分开，避免把运行时对象引用写入存档。

通知事件 `WalletChanged.java`：

```java
package com.meowody.game.event;

/** Emit after the shared wallet model has been updated. */
public record WalletChanged() { }
```

这个事件只通知“共享钱包发生变化”，余额以 Model 为准。其他系统更新同一个 WalletModel 后，也可以 emit(new WalletChanged())，所有钱包视图再从这个 Model 读取最新值。

若跨系统传递的是数据快照而不是共享 Model，事件应携带明确数据，并由负责 Model 的业务服务更新状态；不要仅更新标签而遗漏 Model。

## 4. View：绑定节点并转发点击

`WalletUI.java`：

```java
package com.meowody.game.ui.wallet;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.meowody.runtime.ui.BaseUI;

public final class WalletUI extends BaseUI {
    private final Label goldLabel;
    private final Label messageLabel;
    private Runnable claimAction;

    public WalletUI() {
        super("ui/wallet.json", "ui");
        goldLabel = require("GoldLabel", Label.class);
        messageLabel = require("MessageLabel", Label.class);
        onClick("ClaimButton", () -> {
            if (claimAction != null) claimAction.run();
        });
    }

    public void bindClaim(Runnable action) {
        claimAction = action;
    }

    public void clearBindings() {
        claimAction = null;
    }

    public void renderGold(int gold) {
        goldLabel.setText("Gold: " + gold);
    }

    public void showMessage(String text) {
        messageLabel.setText(text);
    }
}
```

永久 ClickListener 只在构造函数绑定一次。它读取当前 claimAction，Presenter 每次接管时替换这个动作，关闭时清空。

这对缓存十分重要：缓存的是 WalletUI 节点树，下次打开可能换成新 Presenter。若每次 onOpen 再 addListener，监听会累积；若构造函数捕获第一次 Presenter，缓存重开后会调用已经关闭的 Presenter。

View 的 renderGold/showMessage 仅负责显示，不修改 Model，不通过 App.save 保存数据。

## 5. Presenter：协调业务与中心

`WalletPresenter.java`：

```java
package com.meowody.game.ui.wallet;

import com.meowody.game.event.WalletChanged;
import com.meowody.game.model.WalletModel;
import com.meowody.runtime.App;
import com.meowody.runtime.save.SaveStatus;
import com.meowody.runtime.ui.BaseUI;
import com.meowody.runtime.ui.UIPresenter;

public final class WalletPresenter extends UIPresenter {
    private final WalletModel model;
    private WalletUI view;
    private boolean active;

    public WalletPresenter(WalletModel model) {
        this.model = model;
    }

    @Override public void onAttach(BaseUI root) {
        view = (WalletUI) root;
        view.bindClaim(this::claim);
        defer(view::clearBindings);
        track(App.event().on(WalletChanged.class, event -> {
            if (active) refresh();
        }));
    }

    @Override public void onOpen(Object data) {
        view.showMessage("");
        refresh();
    }

    @Override public void onOpened() {
        active = true;
        refresh();
    }

    @Override public void onSuspend() {
        active = false;
    }

    @Override public void onResume() {
        active = true;
        refresh();
    }

    @Override public void onClosing() {
        active = false;
    }

    @Override public void onClose() {
        active = false;
    }

    private void refresh() {
        view.renderGold(model.gold());
    }

    private void claim() {
        if (!active) return;
        if (model.gold() > Integer.MAX_VALUE - 10) {
            view.showMessage("Gold limit reached");
            return;
        }
        model.addGold(10);
        SaveStatus status = App.save().save("profile", "gold", model.gold());
        App.event().emit(new WalletChanged());
        view.showMessage(status.ok()
                ? "Gold +10; save queued"
                : "Save request failed: " + status.message());
    }
}
```

onAttach 在本轮 Presenter 首次挂载时绑定动作和订阅；onOpen 刷新本次数据，允许重复调用。本例只有打开/恢复后的 Presenter 响应领取动作，退出动画期间以及被页面覆盖时不处理操作。

`track(EventSubscription)` 在最终关闭时断开；`defer(view::clearBindings)` 清掉缓存 View 对旧 Presenter 的动作引用。UIPresenter.close() 是 final，由 UICenter 调用，业务不要覆盖它，也不要自己提前 close 正在使用的 Presenter。

默认 SaveCenter 延迟写入：status.ok() 表示变更接受并进入待写状态，所以文案写“save queued”。它不是已经落盘的承诺。本例保留已发生的内存金币变化；若业务要求保存失败时回滚，必须定义该规则并在 Model/业务服务中实现。需要确认持久化时显式 flushPending 并检查结果。

## 6. Screen：创建共享 Model 并注册 MVP

`WalletScreen.java`：

```java
package com.meowody.game.screen;

import com.meowody.game.model.WalletModel;
import com.meowody.game.ui.wallet.WalletPresenter;
import com.meowody.game.ui.wallet.WalletUI;
import com.meowody.runtime.App;
import com.meowody.runtime.base.BaseScreen;
import com.meowody.runtime.ui.UIKind;
import com.meowody.runtime.ui.UIOptions;

public final class WalletScreen extends BaseScreen {
    private final WalletModel wallet = new WalletModel(
            App.save().load("profile", "gold", Integer.class, 0).data());

    @Override public void show() {
        UIOptions options = new UIOptions();
        options.kind = UIKind.PAGE;
        options.cache = true;
        App.ui().configure("ui/wallet.json", options,
                WalletUI::new,
                () -> new WalletPresenter(wallet));
        App.ui().open("ui/wallet.json");
    }
}
```

这里的 Model 是该 Screen 内的共享业务状态，Presenter 工厂只传 Model，不传 App。它在 App 已初始化后构造，所以可以读取存档；不要在 static 字段中构造 WalletScreen。

在 BaseGame.createFirstScreen 返回 new WalletScreen() 即可接入。页面默认使用 ProjectConfig 的共享1080×1920 ExtendViewport/Batch，不用在 MVP 中单独创建渲染对象。

如果钱包需要跨所有 Screen 存活，Model 应由游戏业务会话持有，再传给各 Presenter，不要让每个 Presenter 各自 new WalletModel。业务会话也不需要保存 App 实例。persistent UI 的 Model 必须至少与该 UI 生命周期一样长。

资源根也可通过 UIOptions.assetRoot 配置；当前 WalletUI 自己明确给出 ui。configure 的 factory 负责 View，presenter 参数负责 Presenter，两者必须匹配。

## 7. 实际生命周期：Presenter 接管回调

当前 UICenter 的实现是：**配置 Presenter 时，同名生命周期回调交给 Presenter，而不再自动调用 View 的对应回调**。

```text
创建或从缓存取出 View
  → 创建新的 Presenter
  → Presenter.onAttach(view)
  → Presenter.onOpen(data)
  → 入场动画
  → Presenter.onOpened()

关闭
  → Presenter.onClosing()
  → 退场动画
  → Presenter.onClose()
  → Presenter.close()：逆序执行 track/defer 清理
  → View 脱离 Stage，进入缓存或最终释放
```

没有 Presenter 时，UICenter 才调用 BaseUI 的 onOpen/onOpened/onClosing/onClose/onSuspend/onResume。

因此不要同时在 WalletUI.onOpen 和 WalletPresenter.onOpen 中各写一半必需的初始化，然后期待框架两边都调用。MVP View 通过普通显式方法接受 Presenter 指令；Presenter 统一决定何时调用。

| 操作 | View | Presenter | Model |
| --- | --- | --- | --- |
| 首次打开 | 新建 | 新建，attach/open | 使用已存在共享 Model |
| 活动页面重复 open，同路径且 multiple=false | 原实例 | 原实例再次 onOpen(data)，不再次 onAttach | 同一个 |
| 关闭并缓存 | 节点保留，动作引用应清空 | onClose 后 close 清理，不缓存 | 由业务持有 |
| 从缓存重新打开 | 可能复用节点 | 工厂创建新实例 | 同一个或业务指定的新 Model |
| 页面被新 PAGE 覆盖 | 暂停显示/输入 | onSuspend，仍未 close | 仍有效 |
| 顶层 PAGE 关闭后恢复 | 原实例 | onResume | 读取最新状态 |
| 缓存淘汰/退出 | dispose，释放布局资源 | 已关闭或在退出时关闭 | 由自身所有者决定 |

onOpen 可能多次调用，不要每次添加不去重的订阅。生命周期级订阅优先放 onAttach；依赖打开参数的订阅应先取消前一轮再绑定新的对象。

## 8. 异步请求如何归属于 Presenter

下面是接入请求的片段，不属于上面金币示例的必需代码：

```java
private NetHandle request;

private void refreshFromServer(String url) {
    if (request != null) request.cancel();
    request = App.net().get(url, result -> {
        if (!active) return;
        if (!result.ok()) {
            view.showMessage(result.message());
            return;
        }
        // 解析协议，校验结果，更新 Model，再刷新 View。
    });
}
```

在 onAttach 注册一次清理：

```java
defer(() -> {
    if (request != null) request.cancel();
});
```

捕获当前字段可以取消最后一笔请求，不需要每次刷新往 cleanup 追加一个旧句柄。网络完成回调在中心 update 派发；cancel 抑制尚未派发的回调，active 检查表达界面目前是否仍接受更新。

如果“页面暂停时仍要更新业务数据”，可让请求回调先更新业务 Model，只在 active 时刷新 View；onResume 再读取 Model。如果请求属于全局业务同步，应由业务服务持有，不要因某个界面关闭就取消。

| 资源/任务 | Presenter 归属方式 |
| --- | --- |
| EventSubscription | track(subscription) |
| DailySubscription / LogSubscription | track(subscription) |
| CoroutineHandle | defer(handle::cancel) |
| NetHandle / DownloadHandle / TimeSyncHandle | defer(handle::cancel)，替换请求先取消旧句柄 |
| LoadHandle / LoadResult | track(handle/result)，只有它确实由此 Presenter 持有时 |

若资源仍被缓存 View 节点使用，不应随着 Presenter 关闭释放其唯一租约；该资源应由 View 或更长寿命的资源组管理。BaseUI 自带的布局租约已经按 View 生命周期管理。

## 9. 打开参数和 Model 注入有什么区别

- Presenter 构造参数用于长期依赖，例如共享钱包、背包、战斗会话。
- open(path,data) 用于本次打开的上下文，例如选中道具 id、提示文案、当前分页。
- View 构造参数用于布局路径/资源根，不宜把本次页面数据固定在构造函数中，因为 View 可能缓存复用。

可以定义数据 record：

```java
public record InventoryOpenData(String selectedItemId) { }
App.ui().open("ui/inventory.json", new InventoryOpenData("item-001"));
```

Presenter.onOpen 中检查/保存这个参数，再根据 Model 刷新 View。重复打开相同活动路径会再次发送 data，不必新建 Presenter；如果业务要独立多实例，设置 UIOptions.multiple=true，每个实例都有各自 Presenter，但可共享同一个业务 Model。

## 10. 弹窗同样可以使用 MVP

弹窗 View 继承 BasePopup，Presenter 仍继承 UIPresenter；以 UIKind.MODAL 注册：

```java
UIOptions options = new UIOptions();
options.kind = UIKind.MODAL;
options.cache = false;
App.ui().configure("ui/confirm.json", options,
        ConfirmPopup::new, ConfirmPresenter::new);
App.ui().open("ui/confirm.json", confirmationData);
```

这是接入片段，需要定义对应 ConfirmPopup/ConfirmPresenter。绑定 Presenter 的弹窗应通过 configure/open 管理；快捷 openPopup(new Popup()) 没有 Presenter 工厂参数，适合 View 自己处理简单交互的弹窗。

纯关闭动作允许 View 调用 BasePopup.close()；涉及删除存档、支付、修改 Model 的确认动作交给 Presenter。确认结束通过 UICenter 或 View 的 close 方法进入正常退场和清理流程。

## 11. 测试 MVP 时验证什么

Model 单独测试，无需 Gdx/GL：增加金币、拒绝非法数额、上限/业务规则和存档迁移。

Presenter 接入测试关注行为：一次点击只处理一次；重复 open 不累积订阅；关闭后事件不再更新旧 View；缓存重开绑定新 Presenter；暂停时不处理用户动作，恢复后展示最新 Model；请求替换与退出取消。

当前桌面 UILifecycleChecks 已覆盖 Presenter 的 attach/open/opened/closing/close 顺序和订阅释放。本文金币示例的五个完整 Java 类已做编译检查，但未附 wallet.json，所以不能把它视为已运行的业务界面。

## 12. 常见错误

- 在 View 里直接 new Presenter：绕过 UICenter 工厂和生命周期，缓存/关闭清理失去统一管理。
- Presenter 保存 App 参数：使用 App 的 static 方法即可；只注入业务 Model/服务。
- Model 引用 Label 或 BaseUI：让 Model 只表达业务状态，显示由 View 完成。
- 每次 onOpen 添加 ClickListener/全局订阅：重复打开可能累积处理；永久节点监听放 View 构造，Presenter 动作覆盖绑定。
- Presenter.close 前没有解除 View 的动作引用：缓存 View 保留旧 Presenter，重开可能执行旧状态。
- 配置了 Presenter 却把必要初始化放 View.onOpen：当前实现不自动调用两者，改成 Presenter 显式调用 View 方法。
- 把 isFinished 当作界面关闭：UIHandle 的 OPEN/SUSPENDED 也代表打开完成；用实际 status 或生命周期判断。
- 存档 save 返回成功就显示“已落盘”：延迟写默认只接受待写数据，需要持久化确认则检查 flushPending。
- 将每个 Presenter 的 Model 当共享单例：Model 生命周期由业务显式管理，UI 层缓存不会自动管理业务状态。
