package com.meowody.runtime.action;

import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction;
import com.badlogic.gdx.utils.Null;
import com.meowody.runtime.cocos.widget.TLoadingBar;

/**
 * Action that animates TLoadingBar.fillAmount from start to end over a duration.
 * Useful for smooth progress transitions with custom interpolation.
 */
public class ProgressAction extends TemporalAction {
	private TLoadingBar target;
	private float start, end;

	/** Creates a ProgressAction that transitions from 0 to 1 over 1 second. */
	public ProgressAction() {
		super(1f);
		start = 0;
		end = 1;
	}

	/** Creates a ProgressAction that transitions from start to end. */
	public ProgressAction(float start, float end) {
		super(1f);
		this.start = start;
		this.end = end;
	}

	/** Creates a ProgressAction that transitions from start to end over a duration. */
	public ProgressAction(float start, float end, float duration) {
		super(duration);
		this.start = start;
		this.end = end;
	}

	/** Creates a ProgressAction with custom interpolation. */
	public ProgressAction(float start, float end, float duration, @Null Interpolation interpolation) {
		super(duration, interpolation);
		this.start = start;
		this.end = end;
	}

	@Override
	public boolean act(float delta) {
		if (target == null) return true;
		return super.act(delta);
	}

	protected void begin() {
		if (target != null) target.setFillAmount(start);
	}

	protected void update(float percent) {
		if (target == null) return;
		if (percent == 0)
			target.setFillAmount(start);
		else if (percent == 1)
			target.setFillAmount(end);
		else
			target.setFillAmount(start + (end - start) * percent);
	}

	public TLoadingBar getTarget() {
		return target;
	}

	public void setTarget(TLoadingBar target) {
		this.target = target;
	}

	public float getStart() {
		return start;
	}

	public void setStart(float start) {
		this.start = start;
	}

	public float getEnd() {
		return end;
	}

	public void setEnd(float end) {
		this.end = end;
	}
}
