package dev.unionkitbot.fabric.core.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Immutable, JSON-friendly task parameters.
 *
 * <p>Accessors return {@link Optional} values instead of sentinel numbers so a
 * missing coordinate cannot be mistaken for {@code 0,0,0}. This is what keeps the
 * navigation module from walking to the world origin when a payload is malformed.
 */
public final class TaskParameters {
	private static final TaskParameters EMPTY = new TaskParameters(Map.of());

	private final Map<String, Object> values;

	private TaskParameters(Map<String, Object> values) {
		this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	/**
	 * @return parameters with no entries
	 */
	public static TaskParameters empty() {
		return EMPTY;
	}

	/**
	 * Creates parameters from a raw map, copying it defensively.
	 *
	 * @param source the source map, may be {@code null}
	 * @return an immutable parameter set
	 */
	public static TaskParameters of(Map<String, ?> source) {
		if (source == null || source.isEmpty()) {
			return EMPTY;
		}
		return new TaskParameters(new LinkedHashMap<>(source));
	}

	/**
	 * @return an immutable view of the raw entries
	 */
	public Map<String, Object> raw() {
		return values;
	}

	/**
	 * @param key the parameter name
	 * @return the raw value when present and non-null
	 */
	public Optional<Object> get(String key) {
		return Optional.ofNullable(values.get(key));
	}

	/**
	 * @param key the parameter name
	 * @return the value as a string, when it is a string
	 */
	public Optional<String> getString(String key) {
		Object value = values.get(key);
		return value instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
	}

	/**
	 * @param key the parameter name
	 * @return the value coerced to a finite double
	 */
	public OptionalDouble getDouble(String key) {
		Object value = values.get(key);
		if (value instanceof Number n) {
			double d = n.doubleValue();
			return Double.isFinite(d) ? OptionalDouble.of(d) : OptionalDouble.empty();
		}
		if (value instanceof String s) {
			try {
				double d = Double.parseDouble(s.trim());
				return Double.isFinite(d) ? OptionalDouble.of(d) : OptionalDouble.empty();
			} catch (NumberFormatException ignored) {
				return OptionalDouble.empty();
			}
		}
		return OptionalDouble.empty();
	}

	/**
	 * @param key the parameter name
	 * @return the value coerced to an integral value
	 */
	public OptionalLong getLong(String key) {
		Object value = values.get(key);
		if (value instanceof Number n) {
			return OptionalLong.of(n.longValue());
		}
		if (value instanceof String s) {
			try {
				return OptionalLong.of(Long.parseLong(s.trim()));
			} catch (NumberFormatException ignored) {
				return OptionalLong.empty();
			}
		}
		return OptionalLong.empty();
	}

	/**
	 * @param key the parameter name
	 * @return the value when it is a boolean
	 */
	public Optional<Boolean> getBoolean(String key) {
		Object value = values.get(key);
		return value instanceof Boolean b ? Optional.of(b) : Optional.empty();
	}

	/**
	 * Requires a coordinate triple.
	 *
	 * <p>Accepts both shapes the protocol uses: the nested {@code position} object
	 * produced by the control API and a flat {@code x}/{@code y}/{@code z} triple.
	 *
	 * @return the position, or empty when any component is missing or non-finite
	 */
	public Optional<Position> position() {
		Object nested = values.get("position");
		if (nested instanceof Map<?, ?> map) {
			OptionalDouble x = numeric(map.get("x"));
			OptionalDouble y = numeric(map.get("y"));
			OptionalDouble z = numeric(map.get("z"));
			if (x.isPresent() && y.isPresent() && z.isPresent()) {
				return Optional.of(new Position(x.getAsDouble(), y.getAsDouble(), z.getAsDouble()));
			}
		}
		OptionalDouble x = getDouble("x");
		OptionalDouble y = getDouble("y");
		OptionalDouble z = getDouble("z");
		if (x.isEmpty() || y.isEmpty() || z.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new Position(x.getAsDouble(), y.getAsDouble(), z.getAsDouble()));
	}

	private static OptionalDouble numeric(Object value) {
		if (value instanceof Number n) {
			double d = n.doubleValue();
			return Double.isFinite(d) ? OptionalDouble.of(d) : OptionalDouble.empty();
		}
		if (value instanceof String s) {
			try {
				double d = Double.parseDouble(s.trim());
				return Double.isFinite(d) ? OptionalDouble.of(d) : OptionalDouble.empty();
			} catch (NumberFormatException e) {
				return OptionalDouble.empty();
			}
		}
		return OptionalDouble.empty();
	}

	/**
	 * Builds a mutable copy with one additional entry.
	 *
	 * @param key the parameter name
	 * @param value the parameter value, may be {@code null} to remove the key
	 * @return a new parameter set
	 */
	public TaskParameters with(String key, Object value) {
		Objects.requireNonNull(key, "key");
		Map<String, Object> copy = new LinkedHashMap<>(values);
		if (value == null) {
			copy.remove(key);
		} else {
			copy.put(key, value);
		}
		return new TaskParameters(copy);
	}

	/**
	 * @return {@code true} when no parameters are present
	 */
	public boolean isEmpty() {
		return values.isEmpty();
	}

	/**
	 * A finite position in the world.
	 *
	 * @param x the x coordinate
	 * @param y the y coordinate
	 * @param z the z coordinate
	 */
	public record Position(double x, double y, double z) {
		public Position {
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
				throw new IllegalArgumentException("position components must be finite");
			}
		}

		/**
		 * @param other the other position
		 * @return the euclidean distance between the two positions
		 */
		public double distanceTo(Position other) {
			double dx = x - other.x;
			double dy = y - other.y;
			double dz = z - other.z;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	@Override
	public String toString() {
		return "TaskParameters" + values;
	}
}
