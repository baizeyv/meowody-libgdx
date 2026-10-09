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
import com.badlogic.gdx.utils.Json;
import com.meowody.runtime.cocos.model.CCExport;
import com.meowody.runtime.cocos.model.GameProjectData;
import com.meowody.runtime.cocos.model.objectdata.group.*;
import com.meowody.runtime.cocos.model.objectdata.widget.*;
import com.meowody.runtime.cocos.model.timelines.CCTimelineActionData;
import com.meowody.runtime.cocos.parser.group.*;
import com.meowody.runtime.cocos.parser.widget.*;

//import net.mwplay.nativefont.NativeFont;
//import net.mwplay.nativefont.NativeFontPaint;

public final class CocosLayoutReader {
    private CocosLayoutReader() { }
    public static CCExport read(FileHandle jsonFile) {
		String json = jsonFile.readString("utf-8");
		Json jj = new Json();
		jj.setTypeName("ctype");
		jj.addClassTag("GameFileData", GameProjectData.class);
		jj.addClassTag("TimelineActionData", CCTimelineActionData.class);
		jj.addClassTag("SingleNodeObjectData", SingleNodeObjectData.class);
		jj.addClassTag("SpriteObjectData", SpriteObjectData.class);
		jj.addClassTag("ProjectNodeObjectData", ProjectNodeObjectData.class);
		jj.addClassTag("LayerObjectData", LayerObjectData.class);
		jj.addClassTag("PanelObjectData", PanelObjectData.class);
		jj.addClassTag("ButtonObjectData", ButtonObjectData.class);
		jj.addClassTag("TextObjectData", TextObjectData.class);
		jj.addClassTag("ImageViewObjectData", ImageViewObjectData.class);
		jj.addClassTag("CheckBoxObjectData", CheckBoxObjectData.class);
		jj.addClassTag("ScrollViewObjectData", ScrollViewObjectData.class);
		jj.addClassTag("TextBMFontObjectData", TextBMFontObjectData.class);
		jj.addClassTag("TextFieldObjectData", TextFieldObjectData.class);
		jj.addClassTag("LoadingBarObjectData", LoadingBarObjectData.class);
		jj.addClassTag("SliderObjectData", SliderObjectData.class);
		jj.addClassTag("PageViewObjectData", PageViewObjectData.class);
		jj.addClassTag("ParticleObjectData", ParticleObjectData.class);
		jj.addClassTag("TextAtlasObjectData", TextAtlasObjectData.class);
		jj.addClassTag("LabelAtlasObjectData", LabelAtlas.class);
		jj.addClassTag("TImageObjectData", TImageObjectData.class);
		jj.setIgnoreUnknownFields(true);
		return jj.fromJson(CCExport.class, json);
    }
}
