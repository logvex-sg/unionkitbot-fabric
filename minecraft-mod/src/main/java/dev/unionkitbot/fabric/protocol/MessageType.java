package dev.unionkitbot.fabric.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Every frame type understood by the control protocol.
 *
 * <p>Types are explicit strings rather than inferred shapes: an unknown type is a
 * hard validation error, which keeps a version mismatch from being silently
 * misinterpreted as a no-op.
 */
public enum MessageType {
	// Requests: Discord bot -> Fabric mod.
	/** Handshake with protocol and identity metadata. */
	HELLO("session.hello", Direction.REQUEST),
	/** Requests an immediate status snapshot. */
	STATUS_GET("status.get", Direction.REQUEST),
	/** Enables automation. */
	START("control.start", Direction.REQUEST),
	/** Disables automation and cancels active work. */
	STOP("control.stop", Direction.REQUEST),
	/** Resets the agent and re-enables automation. */
	RESTART("control.restart", Direction.REQUEST),
	/** Lists queued and finished tasks. */
	TASKS_LIST("tasks.list", Direction.REQUEST),
	/** Fetches one task by identifier. */
	TASK_GET("tasks.get", Direction.REQUEST),
	/** Submits a new task. */
	TASK_CREATE("tasks.create", Direction.REQUEST),
	/** Cancels a task by identifier. */
	TASK_CANCEL("tasks.cancel", Direction.REQUEST),
	/** Cancels every task. */
	TASKS_CANCEL_ALL("tasks.cancelall", Direction.REQUEST),
	/** Returns recent diagnostics entries. */
	LOGS_GET("logs.get", Direction.REQUEST),
	/** Returns the redacted configuration. */
	CONFIG_GET("config.get", Direction.REQUEST),
	/** Applies a partial configuration update. */
	CONFIG_PATCH("config.patch", Direction.REQUEST),
	/** Enables or disables an automation module. */
	MODULES_SET("modules.set", Direction.REQUEST),
	/** Records the current position as home. */
	HOME_SET("home.set", Direction.REQUEST),
	/** Liveness probe. */
	PING("session.ping", Direction.REQUEST),

	// Responses: Fabric mod -> Discord bot.
	/** Successful response to a request. */
	RESPONSE_OK("response.ok", Direction.RESPONSE),
	/** Failed response to a request. */
	RESPONSE_ERROR("response.error", Direction.RESPONSE),

	// Events: Fabric mod -> Discord bot, unsolicited.
	/** Sent once after a successful handshake. */
	WELCOME("session.welcome", Direction.EVENT),
	/** Periodic liveness and status frame. */
	HEARTBEAT("session.heartbeat", Direction.EVENT),
	/** The agent state changed. */
	STATE_CHANGED("state.changed", Direction.EVENT),
	/** A task was submitted. */
	TASK_CREATED("task.created", Direction.EVENT),
	/** A task changed status. */
	TASK_UPDATED("task.updated", Direction.EVENT),
	/** A task completed successfully. */
	TASK_COMPLETED("task.completed", Direction.EVENT),
	/** A task failed permanently. */
	TASK_FAILED("task.failed", Direction.EVENT),
	/** A task was cancelled. */
	TASK_CANCELLED("task.cancelled", Direction.EVENT),
	/** A structured error report. */
	ERROR("system.error", Direction.EVENT),
	/** A diagnostics entry mirrored to connected peers. */
	LOG("system.log", Direction.EVENT),
	/** The configuration changed. */
	CONFIG_CHANGED("config.changed", Direction.EVENT),
	/** The module toggles changed. */
	MODULES_CHANGED("modules.changed", Direction.EVENT),
	/** Graceful shutdown notice; peers should expect the socket to close. */
	GOODBYE("session.goodbye", Direction.EVENT);

	/**
	 * Direction of a frame relative to the Fabric mod.
	 */
	public enum Direction {
		/** Sent by the Discord bot to the mod. */
		REQUEST,
		/** Sent by the mod in reply to a request. */
		RESPONSE,
		/** Sent by the mod without a preceding request. */
		EVENT
	}

	private static final Map<String, MessageType> BY_WIRE_NAME;

	static {
		Map<String, MessageType> map = new LinkedHashMap<>();
		for (MessageType type : values()) {
			map.put(type.wireName, type);
		}
		BY_WIRE_NAME = Collections.unmodifiableMap(map);
	}

	private final String wireName;
	private final Direction direction;

	MessageType(String wireName, Direction direction) {
		this.wireName = wireName;
		this.direction = direction;
	}

	/**
	 * @return the stable wire name
	 */
	public String wireName() {
		return wireName;
	}

	/**
	 * @return which side emits this type
	 */
	public Direction direction() {
		return direction;
	}

	/**
	 * @return {@code true} when the mod may emit this type
	 */
	public boolean isOutbound() {
		return direction != Direction.REQUEST;
	}

	/**
	 * @return {@code true} when the Discord bot may emit this type
	 */
	public boolean isInbound() {
		return direction == Direction.REQUEST;
	}

	/**
	 * Resolves a wire name.
	 *
	 * @param wireName the wire name, case insensitive
	 * @return the matching type, or {@code null} when unknown
	 */
	public static MessageType parse(String wireName) {
		if (wireName == null) {
			return null;
		}
		return BY_WIRE_NAME.get(wireName.trim().toLowerCase(Locale.ROOT));
	}
}
