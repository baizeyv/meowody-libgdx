package com.meowody.runtime.debug;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.Disposable;
import com.meowody.runtime.App;
import com.meowody.runtime.audio.AudioChannelOptions;
import com.meowody.runtime.base.BaseStage;
import com.meowody.runtime.log.LogCenter;
import com.meowody.runtime.log.LogRecord;
import com.meowody.runtime.save.SaveResult;
import com.meowody.runtime.time.TimeSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** Runtime console, logs/filter/export, save and actor inspection, performance graph and custom tabs. */
public final class DebugCenter implements Disposable {

    private record Panel(String title, Supplier<String> text, Supplier<Actor> factory) { }
    private final BaseStage stage = new BaseStage();
    private final com.badlogic.gdx.scenes.scene2d.ui.Skin skin;
    private final Table shield = new Table();
    private final Table panel, tabs = new Table(), filters = new Table(), commandRow = new Table();
    private final Label fps, details;
    private final ScrollPane content;
    private final TextField commandField, tagFilter, textFilter;
    private final Map<String, Panel> panels = new LinkedHashMap<>();
    private final Map<String, Actor> customRoots = new LinkedHashMap<>();
    private final Map<String, DebugCommand> commands = new LinkedHashMap<>();
    private final Set<String> builtins = new java.util.HashSet<>();
    private final ArrayDeque<String> queue = new ArrayDeque<>(), history = new ArrayDeque<>(), output = new ArrayDeque<>();
    private final ArrayDeque<PerformanceSample> samples = new ArrayDeque<>();
    private boolean enabled = true, opened, closed, updating;
    private String selected = "Runtime";
    private float refresh;
    private int levelMask = 31, historyIndex;
    private Actor sceneRoot;
    public int toggleKey = Input.Keys.F12;

    public DebugCenter() {
        skin = createSkin();
        fps = new Label("FPS: --", skin); details = new Label("", skin); details.setWrap(true);
        details.setAlignment(com.badlogic.gdx.utils.Align.topLeft);
        commandField = new TextField("", skin); commandField.setMessageText("Command (help)");
        tagFilter = new TextField("", skin); tagFilter.setMessageText("Log tag"); textFilter = new TextField("", skin); textFilter.setMessageText("Log text/source");
        Table hud = new Table(); hud.setFillParent(true); hud.top().right().pad(8); hud.setTouchable(Touchable.childrenOnly);
        hud.add(fps).padRight(10); hud.add(button("Debug", this::toggle));
        shield.setFillParent(true); shield.setBackground(skin.newDrawable("white", new Color(0, 0, 0, .7f)));
        shield.addListener(new InputListener() { public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) { return true; } }); stage.addActor(shield);
        panel = new Table(skin); panel.setBackground(skin.newDrawable("white", new Color(.06f, .08f, .12f, 1))); panel.pad(12);
        panel.add(new ScrollPane(tabs, skin)).growX().row();
        filters.add(tagFilter).growX().padRight(4); filters.add(textFilter).growX().padRight(4);
        filters.add(button("All/Warnings", () -> levelMask = levelMask == 31 ? 24 : 31)); filters.add(button("Clear logs", LogCenter::clear));
        panel.add(filters).growX().height(56).padTop(6).row();
        content = new ScrollPane(details, skin); content.setFadeScrollBars(false); panel.add(content).grow().padTop(8).row();
        commandRow.add(commandField).growX().padRight(4); commandRow.add(button("Run", this::submit)); commandRow.add(button("Complete", this::completeField));
        panel.add(commandRow).growX().height(56).padTop(8).row();
        Table controls = new Table();
        controls.add(button("Pause", () -> App.time().setScale(0))).padRight(4); controls.add(button("Resume", () -> App.time().setScale(1))).padRight(4);
        controls.add(button("Mute", () -> App.audio().setMuted(!App.audio().isMuted()))).padRight(4); controls.add(button("Export logs", () -> print(exportLogs(), false))).padRight(4);
        controls.add(button("Close", this::close)); panel.add(controls).padTop(8);
        stage.addActor(panel); stage.addActor(hud);
        commandField.setTextFieldListener((field, value) -> { if (value == '\n' || value == '\r') submit(); });
        commandField.addListener(new InputListener() {
            public boolean keyDown(InputEvent event, int key) {
                if (key == Input.Keys.TAB) { completeField(); return true; }
                if (key != Input.Keys.UP && key != Input.Keys.DOWN) return false;
                List<String> list = new ArrayList<>(history); if (list.isEmpty()) return true;
                historyIndex = Math.max(0, Math.min(list.size(), historyIndex + (key == Input.Keys.UP ? -1 : 1)));
                commandField.setText(historyIndex == list.size() ? "" : list.get(historyIndex)); commandField.setCursorPosition(commandField.getText().length()); return true;
            }
        });
        registerPanel("Runtime", this::stats);
        registerPanel("Resources", () -> "Loaded: " + App.resource().getLoadedAssets() + "\nPending: " + App.resource().pendingCount() + "\nLRU: " + App.resource().lruSize() + "\n" + App.resource().getDiagnostics());
        registerPanel("Pools", App.pool()::summary);
        registerPanel("Audio", () -> { StringBuilder text = new StringBuilder(); for (String name : App.audio().channels()) { AudioChannelOptions o = App.audio().channelOptions(name); text.append(name).append(": voices=").append(App.audio().activeCount(name)).append(" volume=").append(o.volume).append(" muted=").append(o.muted).append(" paused=").append(o.paused).append('\n'); } return text.toString(); });
        registerPanel("Network", () -> App.net().snapshot() + "\nHTTP: " + App.net().pendingCount() + "\nDownloads: " + App.download().pendingCount());
        registerPanel("Time", () -> App.time().format() + "\nUTC: " + App.time().format(java.time.ZoneOffset.UTC, TimeSource.BEST_AVAILABLE) + "\nNetwork synced: " + App.time().networkSynced() + "\nDaily subscriptions: " + App.time().subscriptionCount());
        registerPanel("Saves", () -> { StringBuilder text = new StringBuilder("Pending: " + App.save().pendingCount() + "  Commits: " + App.save().commitCount()); for (String part : App.save().listParts()) text.append('\n').append(part).append(": ").append(App.save().inspect(part, false).data()); return text.toString(); });
        registerPanel("Nodes", this::inspectNodes);
        registerPanel("Logs", () -> { StringBuilder text = new StringBuilder(); for (LogRecord record : LogCenter.filter(levelMask, tagFilter.getText(), textFilter.getText())) text.append(record.formatted()).append("\n  ").append(record.source()).append('\n'); return text.toString(); });
        registerPanel("Console", () -> String.join("\n", output));
        registerPanel("performance", "Performance", () -> new PerformanceGraph());
        installCommands(); setOpen(false);
    }
    private static com.badlogic.gdx.scenes.scene2d.ui.Skin createSkin() {
        com.badlogic.gdx.scenes.scene2d.ui.Skin source = App.ui().skin();
        com.badlogic.gdx.scenes.scene2d.ui.Skin result = new com.badlogic.gdx.scenes.scene2d.ui.Skin();
        com.badlogic.gdx.graphics.g2d.BitmapFont font = new com.badlogic.gdx.graphics.g2d.BitmapFont();
        font.getData().setScale(2);
        result.add("default-font", font);
        result.add("white", source.getDrawable("white"), com.badlogic.gdx.scenes.scene2d.utils.Drawable.class);
        Label.LabelStyle label = new Label.LabelStyle(source.get(Label.LabelStyle.class)); label.font = font;
        result.add("default", label);
        com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle button = new com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle(source.get(com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle.class)); button.font = font;
        result.add("default", button);
        TextField.TextFieldStyle field = new TextField.TextFieldStyle(source.get(TextField.TextFieldStyle.class)); field.font = font;
        result.add("default", field);
        result.add("default", new ScrollPane.ScrollPaneStyle(source.get(ScrollPane.ScrollPaneStyle.class)));
        return result;
    }
    private com.badlogic.gdx.scenes.scene2d.ui.TextButton button(String title, Runnable action) {
        com.badlogic.gdx.scenes.scene2d.ui.TextButton button = new com.badlogic.gdx.scenes.scene2d.ui.TextButton(title, skin);
        button.pad(12);
        button.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ChangeListener() {
            public void changed(com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent event, Actor actor) { action.run(); }
        });
        return button;
    }
    public void registerPanel(String name, Supplier<String> text) {
        if (closed) return; panels.put(name, new Panel(name, text, null)); rebuildTabs();
    }
    public boolean registerPanel(String id, String title, Supplier<Actor> factory) {
        if (closed || id == null || id.isEmpty() || factory == null || panels.containsKey(id)) return false;
        panels.put(id, new Panel(title, null, factory)); rebuildTabs(); return true;
    }
    public boolean unregisterPanel(String id) {
        if (panels.remove(id) == null) return false;
        Actor root = customRoots.remove(id); if (root != null) { root.remove(); if (root instanceof Disposable disposable) disposable.dispose(); }
        if (selected.equals(id)) selected = "Runtime"; rebuildTabs(); select(selected); return true;
    }
    private void rebuildTabs() { tabs.clearChildren(); for (Map.Entry<String, Panel> p : panels.entrySet()) tabs.add(button(p.getValue().title, () -> select(p.getKey()))).padRight(4); }
    private void select(String id) {
        selected = id; Panel page = panels.get(id); if (page == null) return;
        if (page.factory() != null) content.setActor(customRoots.computeIfAbsent(id, key -> java.util.Objects.requireNonNull(page.factory().get()))); else content.setActor(details);
        filters.setVisible(id.equals("Logs")); panel.getCell(filters).height(id.equals("Logs") ? 56 : 0);
        refresh = 1; if (id.equals("Console")) stage.setKeyboardFocus(commandField);
    }
    public void setEnabled(boolean value) { if (closed) return; enabled = value; if (!value) { queue.clear(); close(); } stage.getRoot().setVisible(value); }
    public boolean isEnabled() { return enabled && !closed; }
    public boolean isOpen() { return isEnabled() && opened; }
    public boolean capturesInput() { return isOpen(); }
    public void open() { setOpen(true); }
    public void close() { setOpen(false); }
    public void toggle() { setOpen(!opened); }
    public void setOpen(boolean value) {
        if (closed || (value && !enabled)) return;
        opened = value; shield.setVisible(value); panel.setVisible(value); stage.setKeyboardFocus(value ? commandField : null); stage.setScrollFocus(null); refresh = 1;
        if (value) select(selected);
    }
    private void submit() { executeCommand(commandField.getText()); commandField.setText(""); }
    private void completeField() { List<String> values = complete(commandField.getText()); if (values.size() == 1) { commandField.setText(values.get(0)); commandField.setCursorPosition(commandField.getText().length()); } else if (!values.isEmpty()) print(String.join("  ", values), false); }
    public boolean registerCommand(DebugCommand command) {
        if (closed || command == null || command.name() == null || !command.name().matches("[a-z0-9_.-]+") || command.execute() == null || commands.containsKey(command.name())) return false;
        commands.put(command.name(), command); return true;
    }
    public boolean unregisterCommand(String name) { return !builtins.contains(name) && commands.remove(name) != null; }
    public boolean executeCommand(String text) { if (!isEnabled() || text == null || text.length() > 16384 || queue.size() >= 128) return false; queue.addLast(text); return true; }
    public static List<String> parseCommand(String line) {
        List<String> args = new ArrayList<>(); StringBuilder token = new StringBuilder(); char quote = 0; boolean escaped = false, present = false;
        for (int index = 0; index < line.length(); index++) {
            char c = line.charAt(index);
            if (escaped) { token.append(c); escaped = false; present = true; continue; }
            if (c == '\\') { escaped = true; present = true; continue; }
            if (quote != 0) { if (c == quote) quote = 0; else token.append(c); present = true; continue; }
            if (c == '\'' || c == '"') { quote = c; present = true; }
            else if (Character.isWhitespace(c)) { if (present) { args.add(token.toString()); token.setLength(0); present = false; } }
            else { token.append(c); present = true; }
        }
        if (quote != 0 || escaped) throw new IllegalArgumentException("Unterminated quote/escape");
        if (present) args.add(token.toString()); return args;
    }
    public List<String> complete(String line) {
        List<String> args; try { args = parseCommand(line); } catch (IllegalArgumentException ignored) { return List.of(); }
        if (args.size() <= 1 && !line.endsWith(" ")) return commands.keySet().stream().filter(name -> name.startsWith(line)).sorted().toList();
        if (args.isEmpty()) return List.of(); DebugCommand command = commands.get(args.get(0));
        return command == null || command.complete() == null ? List.of() : command.complete().apply(new DebugCommandContext(line, List.copyOf(args.subList(1, args.size()))));
    }
    public void print(String text, boolean error) {
        if (closed) return; if (text.length() > 16384) text = text.substring(0, 16384); output.addLast(text); while (output.size() > 1000) output.removeFirst();
        if (error) LogCenter.error("Console", "%s", text); else LogCenter.info("Console", "%s", text);
    }
    public List<String> history() { return List.copyOf(history); }
    public List<String> output() { return List.copyOf(output); }
    public List<PerformanceSample> samples() { return List.copyOf(samples); }
    private void add(String name, String usage, String description, Function<DebugCommandContext, DebugCommandResult> action) { registerCommand(new DebugCommand(name, description, usage, action, null)); builtins.add(name); }
    private static DebugCommandResult ok(String text) { return DebugCommandResult.success(text); }
    private void installCommands() {
        add("help", "help [command]", "List commands", c -> { StringBuilder text = new StringBuilder(); commands.values().stream().filter(command -> c.args().isEmpty() || command.name().equals(c.args().get(0))).forEach(command -> text.append(command.usage()).append(" - ").append(command.description()).append('\n')); return ok(text.toString()); });
        add("clear", "clear", "Clear console output", c -> { output.clear(); return ok(""); });
        add("fps", "fps", "Current FPS", c -> ok(String.valueOf(Gdx.graphics.getFramesPerSecond())));
        add("runtime.stats", "runtime.stats", "Runtime counters", c -> ok(stats()));
        add("time", "time", "Calendar and network clock", c -> ok(App.time().format() + "\nUTC: " + App.time().format(java.time.ZoneOffset.UTC, TimeSource.BEST_AVAILABLE) + "\nNetwork synced: " + App.time().networkSynced()));
        add("pause", "pause", "Pause game time", c -> { App.time().setScale(0); return ok("Game paused; runtime continues"); });
        add("resume", "resume", "Resume game time", c -> { App.time().setScale(1); return ok("Game resumed"); });
        add("timescale", "timescale <0..10>", "Set game time scale", c -> { if (c.args().size() != 1) return DebugCommandResult.failure("Usage: timescale <0..10>"); float value = Float.parseFloat(c.args().get(0)); if (!Float.isFinite(value) || value < 0 || value > 10) return DebugCommandResult.failure("Expected finite 0..10"); App.time().setScale(value); return ok("Time scale updated"); });
        add("audio.volume", "audio.volume <channel> <0..1>", "Set channel volume", c -> c.args().size() == 2 && App.audio().setVolume(c.args().get(0), Float.parseFloat(c.args().get(1))) ? ok("Volume updated") : DebugCommandResult.failure("Invalid channel/volume"));
        add("audio.mute", "audio.mute <channel> <true|false>", "Mute channel", c -> c.args().size() == 2 && List.of("true", "false").contains(c.args().get(1)) && App.audio().setMuted(c.args().get(0), Boolean.parseBoolean(c.args().get(1))) ? ok("Mute updated") : DebugCommandResult.failure("Usage: audio.mute <channel> <true|false>"));
        add("save.list", "save.list [part]", "List partitions/slots", c -> ok(String.join("\n", c.args().isEmpty() ? App.save().listParts() : App.save().listSlots(c.args().get(0)))));
        add("save.show", "save.show <part> [slot] [disk]", "Read-only inspection", c -> {
            if (c.args().isEmpty()) return DebugCommandResult.failure("Expected partition"); SaveResult<Map<String, Object>> r = App.save().inspect(c.args().get(0), c.args().size() > 2 && c.args().get(2).equals("disk"));
            if (!r.ok()) return DebugCommandResult.failure(r.message()); Object data = r.data(); if (c.args().size() > 1) { if (!r.data().containsKey(c.args().get(1))) return DebugCommandResult.failure("Slot missing"); data = r.data().get(c.args().get(1)); }
            com.badlogic.gdx.utils.Json json = new com.badlogic.gdx.utils.Json(); json.setTypeName(null); return ok(json.prettyPrint(json.toJson(data)));
        });
        add("save.export", "save.export <part> raw | <part> json --plaintext [slot]", "Export disk or plaintext snapshot", c -> {
            if (c.args().size() == 2 && c.args().get(1).equals("raw")) return ok(exportSave(c.args().get(0), "", true, true));
            if (c.args().size() >= 3 && c.args().get(1).equals("json") && c.args().get(2).equals("--plaintext")) return ok(exportSave(c.args().get(0), c.args().size() > 3 ? c.args().get(3) : "", false, false));
            return DebugCommandResult.failure("Use raw or json --plaintext [slot]");
        });
    }
    public String stats() {
        return "Java heap: " + Gdx.app.getJavaHeap() / 1048576 + " MB\nResources pending/LRU: " + App.resource().pendingCount() + " / " + App.resource().lruSize()
            + "\nDownloads/HTTP: " + App.download().pendingCount() + " / " + App.net().pendingCount() + "\nUI active/cache: " + App.ui().activeCount() + " / " + App.ui().cacheSize()
            + "\nCoroutines/listeners: " + App.coroutine().size() + " / " + App.event().listenerCount() + "\nSave pending/commits: " + App.save().pendingCount() + " / " + App.save().commitCount()
            + "\nTime scale: " + App.time().scale() + "\nLog dropped: " + LogCenter.dropped();
    }
    public String exportSave(String part, String slot, boolean disk, boolean raw) {
        SaveResult<Map<String, Object>> inspected = App.save().inspect(part, raw || disk);
        if (!inspected.ok()) return "Error: " + inspected.message();
        com.badlogic.gdx.files.FileHandle target = App.storageDirectory().child("debug_exports/" + part + "_" + System.nanoTime() + (raw ? ".sav" : ".json"));
        try {
            target.parent().mkdirs();
            if (raw) new com.badlogic.gdx.files.FileHandle(App.save().partPath(part)).copyTo(target);
            else { Object value = inspected.data(); if (!slot.isEmpty()) { if (!inspected.data().containsKey(slot)) return "Error: slot missing"; value = inspected.data().get(slot); } com.badlogic.gdx.utils.Json json = new com.badlogic.gdx.utils.Json(); json.setTypeName(null); target.writeString(json.prettyPrint(json.toJson(value)), false, "UTF-8"); }
            return target.path();
        } catch (RuntimeException error) { return "Error: " + error; }
    }
    public String exportLogs() { com.badlogic.gdx.files.FileHandle file = App.storageDirectory().child("debug_exports/log_" + System.nanoTime() + ".txt"); file.parent().mkdirs(); file.writeString(String.join("\n", LogCenter.history()), false, "UTF-8"); return file.path(); }
    public void setSceneRoot(Actor root) { sceneRoot = root; }
    private String inspectNodes() {
        StringBuilder text = new StringBuilder(); int[] budget = {2000}; if (sceneRoot != null) appendActor(text, sceneRoot, 0, budget); appendActor(text, App.ui().stage().getRoot(), 0, budget); return text.toString();
    }
    private static void appendActor(StringBuilder text, Actor actor, int depth, int[] budget) {
        if (budget[0]-- <= 0 || depth > 16) return;
        text.append("  ".repeat(depth)).append(actor.getName() == null ? actor.getClass().getSimpleName() : actor.getName()).append(" [").append(actor.getClass().getSimpleName()).append("] x=").append(actor.getX()).append(" y=").append(actor.getY()).append(" size=").append(actor.getWidth()).append('x').append(actor.getHeight()).append(" visible=").append(actor.isVisible()).append(" input=").append(actor.getTouchable()).append('\n');
        if (actor instanceof Group group) for (Actor child : group.getChildren()) appendActor(text, child, depth + 1, budget);
    }
    public InputProcessor input() {
        return new InputMultiplexer(new InputAdapter() {
            public boolean keyDown(int key) {
                if (enabled && (key == toggleKey || key == Input.Keys.F3)) { toggle(); return true; }
                if (isOpen() && (key == Input.Keys.ESCAPE || key == Input.Keys.BACK)) { close(); return true; } return false;
            }
        }, stage, new InputAdapter() {
            public boolean keyDown(int key) { return capturesInput(); }
            public boolean keyUp(int key) { return capturesInput(); }
            public boolean keyTyped(char value) { return capturesInput(); }
            public boolean touchDown(int x, int y, int pointer, int button) { return capturesInput(); }
            public boolean touchUp(int x, int y, int pointer, int button) { return capturesInput(); }
            public boolean touchDragged(int x, int y, int pointer) { return capturesInput(); }
            public boolean mouseMoved(int x, int y) { return capturesInput(); }
            public boolean scrolled(float x, float y) { return capturesInput(); }
        });
    }
    public void update(double delta) {
        if (!isEnabled() || updating) return; updating = true;
        try {
            List<String> ready = new ArrayList<>(queue); queue.clear();
            for (String line : ready) {
                if (!isEnabled()) break; history.addLast(line); while (history.size() > 100) history.removeFirst(); historyIndex = history.size(); print("> " + line, false);
                try { List<String> args = parseCommand(line); if (args.isEmpty()) continue; DebugCommand command = commands.get(args.get(0)); if (command == null) { print("Unknown command: " + args.get(0), true); continue; }
                    DebugCommandResult result = command.execute().apply(new DebugCommandContext(line, List.copyOf(args.subList(1, args.size())))); if (!result.text().isEmpty()) print(result.text(), !result.ok());
                } catch (RuntimeException error) { print("Command failed: " + error, true); }
            }
            refresh += Math.max(0, (float) delta);
            if (refresh >= .25f) {
                refresh = 0; fps.setText("FPS: " + Gdx.graphics.getFramesPerSecond()); samples.addLast(new PerformanceSample(App.time().realElapsed(), Gdx.graphics.getFramesPerSecond(), (float) delta * 1000, Gdx.app.getJavaHeap())); while (samples.size() > 240) samples.removeFirst();
                Panel page = panels.get(selected); if (isOpen() && page != null && page.text() != null) try { details.setText(page.text().get()); } catch (RuntimeException e) { details.setText("Panel failed: " + e); }
            }
        } finally { updating = false; }
    }
    private final class PerformanceGraph extends Actor implements Disposable {
        private final ShapeRenderer shape = new ShapeRenderer();
        PerformanceGraph() { setSize(600, 200); }
        public void draw(Batch batch, float alpha) {
            if (samples.size() < 2) return;
            batch.end(); shape.setProjectionMatrix(batch.getProjectionMatrix()); shape.setTransformMatrix(batch.getTransformMatrix()); shape.begin(ShapeRenderer.ShapeType.Line);
            shape.setColor(Color.CYAN); List<PerformanceSample> values = samples(); float maximum = 1; for (PerformanceSample sample : values) maximum = Math.max(maximum, sample.frameMs());
            for (int index = 1; index < values.size(); index++) shape.line(getX() + (index - 1) * getWidth() / (values.size() - 1), getY() + values.get(index - 1).frameMs() / maximum * getHeight(), getX() + index * getWidth() / (values.size() - 1), getY() + values.get(index).frameMs() / maximum * getHeight());
            shape.end(); batch.begin();
        }
        public void dispose() { shape.dispose(); }
    }
    public void render(float delta) { if (!isEnabled()) return; update(delta); stage.act(delta); stage.getViewport().apply(); stage.draw(); }
    public BaseStage stage() { return stage; }
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        stage.resize(width, height);
        panel.setBounds(24, 24, Math.max(1, stage.getWidth() - 48), Math.max(1, stage.getHeight() - 128));
    }
    public boolean isClosed() { return closed; }
    public void shutdown() { if (closed) return; close(); closed = true; enabled = false; queue.clear(); for (Actor root : customRoots.values()) if (root instanceof Disposable disposable) disposable.dispose(); customRoots.clear(); panels.clear(); commands.clear(); stage.dispose(); skin.dispose(); }
    public void dispose() { shutdown(); }
}
