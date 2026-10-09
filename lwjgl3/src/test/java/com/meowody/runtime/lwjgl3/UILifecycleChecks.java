package com.meowody.runtime.lwjgl3;

import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.meowody.runtime.App;
import com.meowody.runtime.debug.DebugCenter;
import com.meowody.runtime.debug.DebugCommand;
import com.meowody.runtime.debug.DebugCommandResult;
import com.meowody.runtime.log.LogCenter;
import com.meowody.runtime.ui.BaseUI;
import com.meowody.runtime.ui.UICenter;
import com.meowody.runtime.ui.UIHandle;
import com.meowody.runtime.ui.UIKind;
import com.meowody.runtime.ui.UIOptions;
import com.meowody.runtime.ui.UIPresenter;
import com.meowody.runtime.ui.UIStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class UILifecycleChecks {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class View extends BaseUI {
        int opens, opened, closes, closing, suspended, resumed;
        Object data;
        View() { super(); Group root = new Group(); root.setSize(320, 200); setRoot(root); }
        public void onOpen(Object data) { opens++; this.data = data; }
        public void onOpened() { opened++; listen(String.class, value -> { }); }
        public void onClosing() { closing++; }
        public void onClose() { closes++; }
        public void onSuspend() { suspended++; }
        public void onResume() { resumed++; }
    }
    static void run(Runnable switchScreen) {
        BaseUI cocosA = new BaseUI("ui/popup.json") { };
        BaseUI cocosB = new BaseUI("ui/popup.json") { };
        check(cocosA.root() != cocosB.root(), "Shared Cocos editor creates independent UI trees");
        cocosA.dispose();
        check(!cocosB.isDisposed() && cocosB.root().getChildren().size > 0, "Disposing one Cocos UI preserves the other tree");
        cocosB.dispose();
        UICenter ui = App.ui(); int listeners = App.event().listenerCount(); AtomicInteger created = new AtomicInteger();
        UIOptions page = new UIOptions(); page.kind = UIKind.PAGE;
        check(ui.configure("test/page-a", page, () -> { created.incrementAndGet(); return new View(); }, null), "Configure page");
        ui.configure("test/page-b", page, () -> new View(), null);
        UIHandle first = ui.open("test/page-a", "first"); View root = (View) first.root();
        UIHandle second = ui.open("test/page-b", "second");
        check(first.status() == UIStatus.SUSPENDED && root.suspended == 1 && second.status() == UIStatus.OPEN, "Opening page suspends prior page");
        ui.close(second.id()); check(first.status() == UIStatus.OPEN && root.resumed == 1, "Closing top page resumes previous page");
        check(ui.open("test/page-a", "new-data").id() == first.id() && root.data.equals("new-data"), "Non-multiple reopen reuses handle and delivers new data");
        ui.close(first.id()); check(first.root() == null && root.closes == 1 && App.event().listenerCount() == listeners, "Cached close detaches root and cleans view subscriptions");
        UIHandle reused = ui.open("test/page-a", "reopened"); check(reused.root() == root && reused.id() != first.id() && created.get() == 1 && root.opened == 2, "Cache reuses root with a fresh handle/open lifecycle"); ui.close(reused.id());
        ui.clearCache(); check(root.isDisposed() && root.closes == 2, "Cache eviction disposes root without duplicate close hook");

        List<String> lifecycle = new ArrayList<>(); UIOptions window = new UIOptions();
        ui.configure("test/presenter", window, () -> new View(), () -> new UIPresenter() {
            public void onAttach(BaseUI root) { lifecycle.add("attach"); }
            public void onOpen(Object data) { lifecycle.add("open"); track(App.event().on(Integer.class, value -> { })); }
            public void onOpened() { lifecycle.add("opened"); }
            public void onClosing() { lifecycle.add("closing"); }
            public void onClose() { lifecycle.add("close"); }
        });
        UIHandle presented = ui.open("test/presenter", "data"); ui.close(presented.id());
        check(lifecycle.equals(List.of("attach", "open", "opened", "closing", "close")) && App.event().listenerCount() == listeners, "Presenter lifecycle and subscription disconnect");
        UIOptions animation = new UIOptions(); animation.enterAnimation = () -> Actions.delay(.04f); animation.exitAnimation = () -> Actions.delay(.04f);
        ui.configure("test/animated", animation, () -> new View(), null); UIHandle animated = ui.open("test/animated");
        check(animated.status() == UIStatus.ENTERING, "Entry animation state"); ui.render(.06f); ui.render(0); check(animated.status() == UIStatus.OPEN, "Entry finishes before opened state");
        ui.close(animated.id()); check(animated.status() == UIStatus.EXITING, "Exit animation state"); ui.render(.06f); ui.render(0); check(animated.status() == UIStatus.CLOSED, "Exit completes before retiring UI");
        animation.animationTimeout = .05; animation.enterAnimation = () -> Actions.forever(Actions.delay(10)); animation.exitAnimation = null;
        ui.configure("test/timeout", animation, () -> new View(), null); UIHandle timeout = ui.open("test/timeout"); ui.render(.06f); check(timeout.status() == UIStatus.OPEN, "Timed-out transition completes safely"); ui.close(timeout.id());

        AtomicInteger callbacks = new AtomicInteger(); ui.configure("test/async", window, () -> new View(), null);
        UIHandle async = ui.openAsync("test/async", "async", handle -> callbacks.incrementAndGet()); check(callbacks.get() == 0 && !async.isFinished(), "Async never invokes callbacks on caller stack"); ui.update(0); check(async.isOpen() && callbacks.get() == 1, "Async opens/delivers during update"); ui.close(async.id());
        UIHandle cancelled = ui.openAsync("test/async", "cancel", handle -> callbacks.incrementAndGet()); cancelled.cancel(); ui.update(0); check(cancelled.status() == UIStatus.CANCELLED && callbacks.get() == 1, "Cancelling queued UI suppresses callback");
        UIOptions multiple = new UIOptions(); multiple.multiple = true;
        ui.configure("test/multiple", multiple, () -> new View(), null); UIHandle one = ui.open("test/multiple"), two = ui.open("test/multiple"); check(one.id() != two.id() && one.root() != two.root(), "Multiple configuration creates separate roots"); ui.close(one.id()); ui.close(two.id());
        UIOptions modal = new UIOptions(); modal.kind = UIKind.MODAL;
        ui.configure("test/modal", modal, () -> new View(), null); UIHandle top = ui.open("test/modal"); check(ui.back() && top.status() == UIStatus.CLOSED, "Back dismisses modal first");
        window.persistent = true; ui.configure("test/persistent", window, () -> new View(), null); UIHandle persistent = ui.open("test/persistent"); switchScreen.run(); check(persistent.isOpen(), "Persistent UI survives Screen switch"); ui.close(persistent.id());
        ui.clearCache(); check(App.event().listenerCount() == 1 && ui.currentPage() != null, "Lifecycle checks must leave only demo page subscription");

        DebugCenter debug = App.debug(); List<String> arguments = new ArrayList<>();
        debug.registerCommand(new DebugCommand("test.echo", "Test", "test.echo <args>", context -> { arguments.addAll(context.args()); return DebugCommandResult.success("ok"); }, null));
        debug.executeCommand("test.echo \"two words\""); check(arguments.isEmpty(), "Debug execution queued until update"); debug.update(0); check(arguments.equals(List.of("two words")) && debug.complete("test.e").equals(List.of("test.echo")), "Console quoting and completion");
        debug.executeCommand("audio.volume SFX 0.5"); debug.executeCommand("timescale 0.5"); debug.update(0); check(App.audio().soundVolume() == .5f && App.time().scale() == .5f, "Built-in commands drive centers"); App.time().setScale(1);
        debug.setEnabled(false); check(!debug.executeCommand("fps") && !debug.capturesInput(), "Disabled Debug cannot execute or capture"); debug.setEnabled(true);
        check(debug.unregisterCommand("test.echo") && !debug.unregisterCommand("help"), "Built-in commands cannot be removed");
        check(debug.registerPanel("test-panel", "Test panel", Group::new) && debug.unregisterPanel("test-panel"), "Custom Actor panel lifecycle");
        LogCenter.info("Parity", "UI lifecycle, cache, transitions, presenter and console checks passed");
    }
}
