package com.meowody.runtime.cocos;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.utils.Json;
import com.meowody.runtime.cocos.model.ObjectData;
import com.meowody.runtime.ui.BaseUI;

/** Run with java; no graphics backend required. */
public final class CocosLayoutActionCheck {
    public static void main(String[] args) {
        ObjectData stretch = new Json().fromJson(ObjectData.class,
            "{\"StretchWidthEnable\":true,\"StretchHeightEnable\":true,"
                + "\"HorizontalEdge\":\"BothEdge\",\"VerticalEdge\":\"BothEdge\","
                + "\"AnchorPoint\":{\"ScaleX\":0.5,\"ScaleY\":0.5}}");
        Group designRoot = new Group();
        designRoot.setSize(200, 100);
        Group child = new Group();
        child.setBounds(10, 5, 170, 80);
        child.setOrigin(85, 40);
        CocosLayoutAction.attach(child, designRoot, stretch);
        designRoot.addActor(child);
        BaseUI page = new BaseUI() {
            { setRoot(designRoot); }
        };
        Group parent = page;

        ObjectData pinned = new ObjectData();
        pinned.HorizontalEdge = "RightEdge";
        pinned.VerticalEdge = "TopEdge";
        Actor corner = new Actor();
        corner.setBounds(140, 60, 20, 10);
        CocosLayoutAction.attach(corner, child, pinned);
        child.addActor(corner);
        // Runtime path: PAGE -> BaseUI -> exported root -> nested constraints.
        page.setSize(300, 200);
        page.act(0);
        bounds(parent, 0, 0, 300, 200);
        bounds(child, 10, 5, 270, 180);
        bounds(corner, 240, 160, 20, 10);
        equal(child.getOriginX(), 135);
        equal(child.getOriginY(), 90);
        page.setSize(200, 100);
        page.act(0);
        bounds(child, 10, 5, 170, 80);
        bounds(corner, 140, 60, 20, 10);
        parent.setSize(20, 10);
        parent.act(0);
        bounds(child, 10, 5, 0, 0);

        parent.setSize(200, 100);
        Actor proportional = new Actor();
        proportional.setBounds(20, 10, 80, 40);
        ObjectData single = new ObjectData();
        single.StretchWidthEnable = true;
        single.StretchHeightEnable = true;
        single.HorizontalEdge = "RightEdge";
        single.VerticalEdge = "TopEdge";
        CocosLayoutAction.attach(proportional, parent, single);
        parent.addActor(proportional);
        Actor untouched = new Actor();
        untouched.setBounds(1, 2, 3, 4);
        CocosLayoutAction.attach(untouched, parent, new ObjectData());
        parent.addActor(untouched);
        parent.setSize(400, 200);
        parent.act(0);
        bounds(proportional, 140, 70, 160, 80);
        bounds(untouched, 101, 52, 3, 4);
        parent.setSize(200, 100);
        parent.act(0);
        bounds(untouched, 1, 2, 3, 4);
        parent.setSize(400, 200);
        parent.act(0);
        bounds(untouched, 101, 52, 3, 4);

        ObjectData mixed = new ObjectData();
        mixed.HorizontalEdge = "LeftEdge";
        mixed.VerticalEdge = "None";
        Actor mixedActor = new Actor();
        mixedActor.setBounds(20, 30, 40, 50);
        CocosLayoutAction.attach(mixedActor, parent, mixed);
        parent.addActor(mixedActor);
        parent.setSize(500, 300);
        parent.act(0);
        bounds(mixedActor, 20, 80, 40, 50);
        parent.setSize(400, 200);
        parent.act(0);
        bounds(mixedActor, 20, 30, 40, 50);

        Actor margins = new Actor();
        margins.setBounds(12, 13, 20, 30);
        stretch.LeftMargin = 0f;
        stretch.RightMargin = 5f;
        stretch.BottomMargin = 0f;
        stretch.TopMargin = 6f;
        CocosLayoutAction.attach(margins, parent, stretch);
        parent.addActor(margins);
        parent.act(0);
        bounds(margins, 0, 0, 395, 194);
        System.out.println("Cocos layout checks passed");
    }

    private static void bounds(Actor actor, float x, float y, float width, float height) {
        equal(actor.getX(), x);
        equal(actor.getY(), y);
        equal(actor.getWidth(), width);
        equal(actor.getHeight(), height);
    }

    private static void equal(float actual, float expected) {
        if (Math.abs(actual - expected) > 0.001f)
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
