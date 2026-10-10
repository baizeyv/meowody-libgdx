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
import com.meowody.runtime.cocos.BaseCocoStudioUIEditor;
import com.meowody.runtime.cocos.model.objectdata.widget.ParticleObjectData;
import com.meowody.runtime.cocos.parser.WidgetParser;
import com.meowody.runtime.cocos.particleutil.CCParticleActor;

public class CCParticle extends WidgetParser<ParticleObjectData> {
	@Override
	public String getClassName () {
		return "ParticleObjectData";
	}

	@Override
	public Actor parse (BaseCocoStudioUIEditor editor, ParticleObjectData widget) {
		CCParticleActor ccParticleActor = new CCParticleActor(editor.findParticePath(widget.FileData));
		editor.own(ccParticleActor::dispose);
		ccParticleActor.setBlendAdditive(true);

		return ccParticleActor;
	}
}
