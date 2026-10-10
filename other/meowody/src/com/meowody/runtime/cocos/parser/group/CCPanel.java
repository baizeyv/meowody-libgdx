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

package com.meowody.runtime.cocos.parser.group;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.meowody.runtime.cocos.BaseCocoStudioUIEditor;
import com.meowody.runtime.cocos.model.Size;
import com.meowody.runtime.cocos.model.objectdata.group.PanelObjectData;
import com.meowody.runtime.cocos.parser.GroupParser;
import com.meowody.runtime.cocos.util.NUtils;

/**
 * tip 还未支持单色背景属性, 背景图片在Cocostudio里面并不是铺满, 而是居中
 */
public class CCPanel extends GroupParser<PanelObjectData> {

	@Override
	public String getClassName () {
		return "PanelObjectData";
	}

	@Override
	public Actor parse (BaseCocoStudioUIEditor editor, PanelObjectData widget) {
		Table table = new Table();

		Size size = widget.Size;
		if (widget.ComboBoxIndex == 0) { // 无颜色

		} else if (widget.ComboBoxIndex == 1 && widget.BackColorAlpha != 0) {// 单色
//			Pixmap pixmap = new Pixmap((int)size.X, (int)size.Y, Format.RGBA8888);
			Pixmap pixmap = new Pixmap(4,4,Format.RGBA8888);

			pixmap.setColor(NUtils.getColor(widget.SingleColor, widget.BackColorAlpha));

			pixmap.fill();

			Texture texture = new Texture(pixmap);
			editor.own(texture);
			Drawable d = new TextureRegionDrawable(new TextureRegion(texture));
			table.setBackground(d);
			pixmap.dispose();
		}

		if (widget.FileData != null) {// Panel的图片并不是拉伸平铺的!!.但是这里修改为填充
			Drawable tr = editor.findDrawable(widget, widget.FileData);
			if (tr != null) {
				Image bg = new Image(tr);
				bg.setPosition((size.X - bg.getWidth()) / 2, (size.Y - bg.getHeight()) / 2);
				// bg.setFillParent(true);
				bg.setTouchable(Touchable.disabled);

				table.addActor(bg);
			}
		}

		table.setClip(widget.ClipAble);

		return table;
	}

}
