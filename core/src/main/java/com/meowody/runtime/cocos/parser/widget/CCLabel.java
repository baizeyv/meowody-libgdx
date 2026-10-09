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
package com.meowody.runtime.cocos.parser.widget;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.Align;
import com.meowody.runtime.cocos.BaseCocoStudioUIEditor;
import com.meowody.runtime.cocos.model.objectdata.widget.TextObjectData;
import com.meowody.runtime.cocos.parser.WidgetParser;
import com.meowody.runtime.cocos.widget.TTFLabel;
import com.meowody.runtime.cocos.widget.TTFLabelStyle;

public class CCLabel extends WidgetParser<TextObjectData> {

    @Override
    public String getClassName() {
        return "TextObjectData";
    }

    @Override
    public Actor parse(BaseCocoStudioUIEditor editor, TextObjectData widget) {

        final TTFLabelStyle labelStyle = editor.createLabelStyle(widget, widget.LabelText, com.badlogic.gdx.graphics.Color.WHITE);

        TTFLabel label = new TTFLabel(widget.LabelText, labelStyle);
        // 水平
        int h = Integer.MAX_VALUE;
		if ("HT_Center".equals(widget.HorizontalAlignmentType)) {
            h = Align.center;
        } else if ("HT_Left".equals(widget.HorizontalAlignmentType)) {
            h = Align.left;
        } else if ("HT_Right".equals(widget.HorizontalAlignmentType)) {
            h = Align.right;
        }

        // 垂直
        int v = Integer.MAX_VALUE;
        if ("HT_Center".equals(widget.VerticalAlignmentType)) {
            v = Align.center;
        } else if ("HT_Top".equals(widget.VerticalAlignmentType)) {
            v = Align.top;
        } else if ("HT_Bottom".equals(widget.VerticalAlignmentType)) {
            v = Align.bottom;
        }

        if (v != Integer.MAX_VALUE) {
            label.setAlignment(h, v);
        }

        return label;
    }
}
