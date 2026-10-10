package com.meowody.runtime.cocos;

import com.badlogic.gdx.scenes.scene2d.Action;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.meowody.runtime.cocos.model.ObjectData;

/** Applies exported constraints when the parent size changes, before children act. */
final class CocosLayoutAction extends Action {
    private final ObjectData data;
    private final float left, right, bottom, top;
    private final float widthRatio, heightRatio, positionRatioX, positionRatioY;
    private final float anchorX, anchorY;
    private final float centerOffsetX, centerOffsetY;
    private float parentWidth = Float.NaN, parentHeight = Float.NaN;

    static void attach(Actor actor, Group parent, ObjectData data) {
        actor.addAction(new CocosLayoutAction(actor, parent, data));
    }

    private static boolean unanchored(String edge) {
        return edge == null || edge.isEmpty() || "None".equals(edge);
    }

    private CocosLayoutAction(Actor actor, Group parent, ObjectData data) {
        this.data = data;
        anchorX = data.AnchorPoint == null ? 0 : data.AnchorPoint.ScaleX;
        anchorY = data.AnchorPoint == null ? 0 : data.AnchorPoint.ScaleY;
        centerOffsetX = actor.getX() - parent.getWidth() / 2;
        centerOffsetY = actor.getY() - parent.getHeight() / 2;
        left = data.LeftMargin == null ? actor.getX() : data.LeftMargin;
        right = data.RightMargin == null ? parent.getWidth() - actor.getRight() : data.RightMargin;
        bottom = data.BottomMargin == null ? actor.getY() : data.BottomMargin;
        top = data.TopMargin == null ? parent.getHeight() - actor.getTop() : data.TopMargin;
        widthRatio = ratio(actor.getWidth(), parent.getWidth());
        heightRatio = ratio(actor.getHeight(), parent.getHeight());
        positionRatioX = ratio(actor.getX() + actor.getOriginX(), parent.getWidth());
        positionRatioY = ratio(actor.getY() + actor.getOriginY(), parent.getHeight());
    }

    private static float ratio(float value, float size) {
        return size == 0 ? 0 : value / size;
    }

    private static boolean both(String edge) {
        return "BothEdge".equals(edge) || "Center".equals(edge);
    }

    @Override
    public boolean act(float delta) {
        Group parent = actor.getParent();
        if (parent == null) return false;
        float pw = parent.getWidth(), ph = parent.getHeight();
        boolean resized = pw != parentWidth || ph != parentHeight;
        if (pw != parentWidth) {
            String edge = data.HorizontalEdge;
            if (data.StretchWidthEnable)
                actor.setWidth(Math.max(0, both(edge) ? pw - left - right : pw * widthRatio));
            if (both(edge))
                actor.setX(data.StretchWidthEnable ? left : pw * positionRatioX - anchorX * actor.getWidth());
            else if ("RightEdge".equals(edge) || "Right".equals(edge))
                actor.setX(pw - right - actor.getWidth());
            else if (data.StretchWidthEnable || "LeftEdge".equals(edge) || "Left".equals(edge))
                actor.setX(left);
            else if (unanchored(edge))
                actor.setX(pw / 2 + centerOffsetX);
            parentWidth = pw;
        }
        if (ph != parentHeight) {
            String edge = data.VerticalEdge;
            if (data.StretchHeightEnable)
                actor.setHeight(Math.max(0, both(edge) ? ph - bottom - top : ph * heightRatio));
            if (both(edge))
                actor.setY(data.StretchHeightEnable ? bottom : ph * positionRatioY - anchorY * actor.getHeight());
            else if ("TopEdge".equals(edge) || "Top".equals(edge))
                actor.setY(ph - top - actor.getHeight());
            else if (data.StretchHeightEnable || "BottomEdge".equals(edge) || "Bottom".equals(edge))
                actor.setY(bottom);
            else if (unanchored(edge))
                actor.setY(ph / 2 + centerOffsetY);
            parentHeight = ph;
        }
        if (resized && (data.StretchWidthEnable || data.StretchHeightEnable))
            actor.setOrigin(anchorX * actor.getWidth(), anchorY * actor.getHeight());
        return false;
    }
}
