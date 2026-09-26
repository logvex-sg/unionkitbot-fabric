package dev.unionkitbot.fabric.module;

import dev.unionkitbot.fabric.core.world.ActionResult;
import dev.unionkitbot.fabric.core.world.Observation;

/**
 * The world-facing operations automation modules are allowed to perform.
 *
 * <p>Keeping these behind an interface is what lets the modules be unit tested
 * without a Minecraft client, and it guarantees the whole agent only ever touches
 * client state through a small, auditable surface.
 *
 * <p>Every method must be safe to call at any time: no method may throw when the
 * client, world, player, network handler or entity is missing. Implementations
 * report the problem through {@link ActionResult} instead.
 */
public interface AgentActions {

	/**
	 * Starts pressing a movement input.
	 *
	 * @param input the direction to hold
	 * @return the outcome
	 */
	ActionResult press(MovementInput input);

	/**
	 * Releases a movement input.
	 *
	 * @param input the direction to release
	 * @return the outcome
	 */
	ActionResult release(MovementInput input);

	/**
	 * Releases every movement input this agent may have pressed.
	 *
	 * @return the outcome
	 */
	ActionResult releaseAll();

	/**
	 * Sets the local player's yaw so the player faces a point.
	 *
	 * @param x the target x coordinate
	 * @param z the target z coordinate
	 * @return the outcome
	 */
	ActionResult lookAt(double x, double z);

	/**
	 * Swings the main hand, which is how a hand-off is signalled to the server.
	 *
	 * @return the outcome
	 */
	ActionResult swingMainHand();

	/**
	 * Drops the currently selected stack, one item at a time when requested.
	 *
	 * @param all whether the whole stack should be dropped
	 * @return the outcome
	 */
	ActionResult dropSelected(boolean all);

	/**
	 * Selects a hotbar slot.
	 *
	 * @param slot the slot index, {@code 0} to {@code 8}
	 * @return the outcome
	 */
	ActionResult selectSlot(int slot);

	/**
	 * Writes a local chat message that only this client sees.
	 *
	 * @param message the message text
	 * @return the outcome
	 */
	ActionResult notifyLocal(String message);

	/**
	 * Directional movement inputs the agent is allowed to press.
	 */
	enum MovementInput {
		FORWARD,
		BACK,
		LEFT,
		RIGHT,
		JUMP,
		SNEAK,
		SPRINT
	}

	/**
	 * An implementation that refuses everything. Used by tests and by the headless
	 * agent, where no Minecraft client exists.
	 */
	AgentActions NOOP = new AgentActions() {
		@Override
		public ActionResult press(MovementInput input) {
			return ActionResult.notActionable();
		}

		@Override
		public ActionResult release(MovementInput input) {
			return ActionResult.notActionable();
		}

		@Override
		public ActionResult releaseAll() {
			return ActionResult.idle("nothing to release");
		}

		@Override
		public ActionResult lookAt(double x, double z) {
			return ActionResult.notActionable();
		}

		@Override
		public ActionResult swingMainHand() {
			return ActionResult.notActionable();
		}

		@Override
		public ActionResult dropSelected(boolean all) {
			return ActionResult.notActionable();
		}

		@Override
		public ActionResult selectSlot(int slot) {
			return ActionResult.notActionable();
		}

		@Override
		public ActionResult notifyLocal(String message) {
			return ActionResult.idle("no client attached");
		}
	};
}
