package dev.unionkitbot.fabric.core.world;

import java.util.Locale;
import java.util.Optional;

/**
 * Outcome of a single action issued by an automation module.
 *
 * <p>Actions never throw. The outcome tells the agent's VERIFY step what to do next,
 * and the detail text is preserved in the structured error report instead of being
 * swallowed. Distinguishing {@link Outcome#PROGRESS} from {@link Outcome#COMPLETED}
 * matters: only the module knows whether "arrived" or "still walking" is the case,
 * and guessing from a boolean is how tasks get marked done while still running.
 *
 * @param outcome what happened
 * @param detail a short explanation, never {@code null}
 */
public record ActionResult(Outcome outcome, String detail) {

	/**
	 * What an action achieved.
	 */
	public enum Outcome {
		/** Work advanced but the task is not finished. */
		PROGRESS,
		/** The task finished successfully. */
		COMPLETED,
		/** The action failed and may succeed on a later tick. */
		RETRY,
		/** The action failed and waiting will not help. */
		FAILED,
		/** The action was not attempted because the world was not usable. */
		SKIPPED
	}

	public ActionResult {
		if (outcome == null) {
			throw new IllegalArgumentException("outcome must not be null");
		}
		detail = detail == null || detail.isBlank() ? outcome.name().toLowerCase(Locale.ROOT) : detail;
	}

	/**
	 * @param detail a short explanation
	 * @return a result indicating progress without completion
	 */
	public static ActionResult ok(String detail) {
		return new ActionResult(Outcome.PROGRESS, detail);
	}

	/**
	 * @param detail a short explanation
	 * @return a result indicating successful completion
	 */
	public static ActionResult completed(String detail) {
		return new ActionResult(Outcome.COMPLETED, detail);
	}

	/**
	 * @param detail a short explanation
	 * @return a result indicating a retryable failure
	 */
	public static ActionResult retryable(String detail) {
		return new ActionResult(Outcome.RETRY, detail);
	}

	/**
	 * @param detail a short explanation
	 * @return a result indicating a terminal failure
	 */
	public static ActionResult fatal(String detail) {
		return new ActionResult(Outcome.FAILED, detail);
	}

	/**
	 * @return the standard result for a tick where the world was not usable
	 */
	public static ActionResult notActionable() {
		return new ActionResult(Outcome.SKIPPED, "observation not actionable");
	}

	/**
	 * @param detail a short explanation
	 * @return a result indicating there was nothing to do
	 */
	public static ActionResult idle(String detail) {
		return new ActionResult(Outcome.PROGRESS, detail == null ? "nothing to do" : detail);
	}

	/**
	 * @return {@code true} when the action was carried out and made progress
	 */
	public boolean performed() {
		return outcome == Outcome.PROGRESS || outcome == Outcome.COMPLETED;
	}

	/**
	 * @return {@code true} when the task finished successfully
	 */
	public boolean finished() {
		return outcome == Outcome.COMPLETED;
	}

	/**
	 * @return {@code true} when retrying later could succeed
	 */
	public boolean retryable() {
		return outcome == Outcome.RETRY || outcome == Outcome.SKIPPED;
	}

	/**
	 * @return the detail text
	 */
	public Optional<String> message() {
		return Optional.ofNullable(detail);
	}
}
