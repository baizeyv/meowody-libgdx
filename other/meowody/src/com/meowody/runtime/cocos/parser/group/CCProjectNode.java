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

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.meowody.runtime.cocos.BaseCocoStudioUIEditor;
import com.meowody.runtime.cocos.model.objectdata.group.ProjectNodeObjectData;
import com.meowody.runtime.cocos.parser.GroupParser;

public class CCProjectNode extends GroupParser<ProjectNodeObjectData> {
    @Override
    public String getClassName() {
        return "ProjectNodeObjectData";
    }

    @Override
    public Actor parse(BaseCocoStudioUIEditor editor, ProjectNodeObjectData widget) {
		if (widget.FileData == null){
            return new Group();
        }

//		CocoStudioUIEditor cocoStudioUIEditor = new CocoStudioUIEditor(
//            Gdx.files.internal(editor.getDirName() + widget.FileData.Path),
//            editor.getTtfs(), editor.getBitmapFonts(), editor.getDefaultFont(), editor.getTextureAtlas());
        BaseCocoStudioUIEditor cocoStudioUIEditor = editor.findCoco(widget.FileData);
        Group root = cocoStudioUIEditor.createGroup();
        editor.own(() -> cocoStudioUIEditor.disposeGroup(root));
        return root;
    }
}
