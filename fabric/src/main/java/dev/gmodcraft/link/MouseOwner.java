package dev.gmodcraft.link;

/**
 * Who owns the system mouse (I1). Minecraft's MouseHandler grabs it (InputConstants.grabMouse:
 * SDL_WarpMouseInWindow to its window's centre + SDL relative mouse mode) when it enters a world
 * with its window "active", and warps it back on release. A Minecraft started for GMod
 * (-Dgmodcraft.startHidden=true) or one GMod has connected to never may: GMod owns the mouse and
 * hands Minecraft its look over the link. Before this, a pre-warmed Minecraft that joined its world
 * while its window still counted as focused (focused on creation, before the hide) grabbed the
 * system mouse, and once GMod connected the release was blocked: the grab stayed.
 * <p>Pure bookkeeping for the client mixins (testable without a window).
 */
public final class MouseOwner {
	private boolean systemGrabbed;
	private int blocked;

	/** May Minecraft warp / grab the system mouse right now? */
	public static boolean mayTouchSystemMouse(boolean startHidden, boolean tookOver) {
		return !startHidden && !tookOver;
	}

	/** A grab or release went through to the system (the caller allowed it). */
	public void systemGrab(boolean grabbed) {
		this.systemGrabbed = grabbed;
	}

	/** A grab or release was blocked; returns how many so far (for a log line now and then). */
	public int blockedOne() {
		return ++this.blocked;
	}

	/**
	 * GMod just took over: true when Minecraft still holds a system grab from before (the caller
	 * turns relative mouse mode off, without warping). Clears it.
	 */
	public boolean takeStaleGrab() {
		boolean g = this.systemGrabbed;
		this.systemGrabbed = false;
		return g;
	}
}
