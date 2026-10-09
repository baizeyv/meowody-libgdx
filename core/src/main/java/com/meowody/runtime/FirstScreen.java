package com.meowody.runtime;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.meowody.runtime.base.BaseScreen;
import com.meowody.runtime.log.LogCenter;
import com.meowody.runtime.ui.BasePopup;
import com.meowody.runtime.ui.BaseUI;

/** Cocos JSON example, with scoped tasks and persisted state. */
public class FirstScreen extends BaseScreen {
    public FirstScreen() { super(); }
    public void show() { App.ui().show(new HomeUI()); }

    private static final class CounterChanged {
        final int value;
        CounterChanged(int value) { this.value = value; }
    }
    private static final class HomeUI extends BaseUI {
        private int count;
        private final Label status;
        HomeUI() {
            super( "ui/home.json", "ui");
            count = App.save().load("demo.counter", Integer.class, 0);
            status = require("Status", Label.class);
            status.setText("Saved counter: " + count);
            onClick("SaveButton", () -> {
                App.save().save("demo.counter", ++count);
                App.event().emit(new CounterChanged(count));
            });
            onClick("PopupButton", () -> App.ui().openPopup(new DemoPopup()));
            onClick("TaskButton", () -> {
                status.setText("Waiting for game time...");
                track(App.coroutine().delay(1, false, () -> {
                    status.setText("Task finished!");
                    LogCenter.info("Demo", "Delayed task completed");
                }));
            });
        }
        public void onOpen() {
            listen(CounterChanged.class, event -> status.setText("Saved counter: " + event.value));
        }
    }
    private static final class DemoPopup extends BasePopup {
        DemoPopup() {
            super( "ui/popup.json", "ui");
            onClick("CloseButton", this::close);
        }
    }
}
