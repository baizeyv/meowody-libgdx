/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.meowody.runtime.cocos;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Action;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Array;
import com.meowody.runtime.cocos.model.CCExport;
import com.meowody.runtime.cocos.model.FileData;
import com.meowody.runtime.cocos.model.ObjectData;
import com.meowody.runtime.cocos.parser.group.*;
import com.meowody.runtime.cocos.parser.widget.*;
import com.meowody.runtime.cocos.widget.TTFLabelStyle;

import java.util.HashMap;
import java.util.Map;

//import net.mwplay.nativefont.NativeFont;
//import net.mwplay.nativefont.NativeFontPaint;

public abstract class BaseCocoStudioUIEditor {
    private final java.util.List<com.badlogic.gdx.utils.Disposable> owned = new java.util.ArrayList<>();
    public void own(com.badlogic.gdx.utils.Disposable resource) { owned.add(resource); }
    private final java.util.IdentityHashMap<Group, java.util.List<com.badlogic.gdx.utils.Disposable>> groupOwned = new java.util.IdentityHashMap<>();
    public void disposeGroup(Group root) {
        java.util.List<com.badlogic.gdx.utils.Disposable> resources = groupOwned.remove(root);
        if (resources != null) resources.forEach(com.badlogic.gdx.utils.Disposable::dispose);
    }
    public void disposeOwned() {
        owned.forEach(com.badlogic.gdx.utils.Disposable::dispose); owned.clear();
        for (Group root : new java.util.ArrayList<>(groupOwned.keySet())) disposeGroup(root);
    }
	protected CCExport export;
	protected Map<Actor, Action> actorActionMap;
	protected Map<String, Array<Actor>> actors;
	protected Map<Integer, Actor> actionActors;
	protected Map<String, BaseWidgetParser> parsers;

	public BaseCocoStudioUIEditor(FileHandle file) { this(CocosLayoutReader.read(file)); }
	public BaseCocoStudioUIEditor(CCExport export) {
		parsers = new HashMap<String, BaseWidgetParser>();
		addParser(new CCNode());//节点
		addParser(new CCParticle());//粒子
		addParser(new CCSpriteView());//精灵
		addParser(new CCButton());//按钮
		addParser(new CCCheckBox());//复选框
		addParser(new CCImageView());//图片
		addParser(new CCLabel());//文本
		addParser(new CCLabelBMFont());//艺术字体
		addParser(new CCLoadingBar());//进度条
		addParser(new CCSlider());//滑动条
		addParser(new CCTextField());//输入框
		addParser(new CCPanel());//基础容器
		addParser(new CCPageView());//翻页容器
		addParser(new CCScrollView());//滚动容器
		addParser(new CCTextAtlas());//?
		addParser(new CCLabelAtlas());//?
		addParser(new CCTImageView());//?
		addParser(new CCProjectNode());//项目节点
		addParser(new CCLayer());//图层
		actors = new HashMap<String, Array<Actor>>();
		actionActors = new HashMap<Integer, Actor>();
		//animations = new HashMap<String, Map<Actor, Action>>();
		actorActionMap = new HashMap<Actor, Action>();
		this.export = export;
	}

	public abstract <T extends ObjectData> Drawable findDrawable (T widget, FileData textureFile);

	public abstract BitmapFont createLabelStyleBitmapFint (ObjectData widget, String buttonText, Color color);

	public abstract BaseCocoStudioUIEditor findCoco (FileData fileData);

	public Actor parseWidget (Group parent, ObjectData widget) {
		String className = widget.ctype;
		BaseWidgetParser parser = parsers.get(className);
		if (parser == null) {
			throw new IllegalArgumentException("Unsupported Cocos node: " + className);
		}
		Actor actor = parser.parse(this, widget);
		actor = parser.commonParse(this, widget, parent, actor);
		if (parent != null) CocosLayoutAction.attach(actor, parent, widget);
		return actor;
	}

	public Group createGroup () {
		return createGroup(null);
	}

	/** Parse a scene/node root directly into an existing instance. */
	public Group createGroup (Group target) {
		Actor actor;
        try {
            ObjectData root = export.Content.Content.ObjectData;
            if (target == null) actor = parseWidget(null, root);
            else {
                if (!"SingleNodeObjectData".equals(root.ctype) && !"LayerObjectData".equals(root.ctype))
                    throw new IllegalArgumentException("An existing Group requires a SingleNode or Layer root: " + root.ctype);
                BaseWidgetParser parser = parsers.get(root.ctype);
                actor = parser.commonParse(this, root, null, target);
            }
        }
        catch (RuntimeException error) { owned.forEach(com.badlogic.gdx.utils.Disposable::dispose); owned.clear(); throw error; }
        if (!(actor instanceof Group)) throw new IllegalArgumentException("Cocos root must be Group");
        groupOwned.put((Group) actor, new java.util.ArrayList<>(owned));
        owned.clear();
		actorActionMap.clear();
		actors.clear();
		actionActors.clear();
		return (Group)actor;
	}

	public abstract TTFLabelStyle createLabelStyle (ObjectData widget, String labelText, Color color);

	public abstract BitmapFont findBitmapFont (FileData labelBMFontFile_cnb);

	public abstract TextureRegion findTextureRegion (ObjectData widget, FileData path);

	public abstract String findParticePath (FileData fileData);

	public void addParser (BaseWidgetParser parser) {
		parsers.put(parser.getClassName(), parser);
	}
}
