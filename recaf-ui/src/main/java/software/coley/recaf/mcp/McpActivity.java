package software.coley.recaf.mcp;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A single observable action performed through the MCP bridge.
 * <p>
 * These are rendered in the AI activity window so the user can watch what an agent is doing.
 *
 * @param time     Epoch milliseconds of the action.
 * @param kind     Kind of action, one of {@link #KIND_VIEW}, {@link #KIND_EDIT}, {@link #KIND_QUERY}, {@link #KIND_SYSTEM}.
 * @param tool     MCP tool name that produced the action.
 * @param target   Class/resource the action targeted. May be empty.
 * @param summary  Short human readable description.
 * @param detail   Extended detail, such as decompiled source or unified diff.
 * @param error    {@code true} when the action failed.
 *
 * @author Recaf AI-MCP
 */
public record McpActivity(long time,
                          @Nonnull String kind,
                          @Nonnull String tool,
                          @Nonnull String target,
                          @Nonnull String summary,
                          @Nonnull String detail,
                          boolean error) {
	public static final String KIND_VIEW = "view";
	public static final String KIND_EDIT = "edit";
	public static final String KIND_QUERY = "query";
	public static final String KIND_SYSTEM = "system";

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

	/**
	 * @return Local time string for display.
	 */
	@Nonnull
	public String timeText() {
		return TIME_FORMAT.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
	}

	/**
	 * @param source
	 * 		Source text to split for display purposes.
	 * @param limit
	 * 		Max number of lines to keep.
	 *
	 * @return Lines of the given text, limited to the given count.
	 */
	@Nonnull
	public static List<String> lines(@Nullable String source, int limit) {
		if (source == null || source.isEmpty())
			return List.of();
		List<String> lines = source.lines().toList();
		if (lines.size() <= limit)
			return lines;
		return lines.subList(0, limit);
	}
}
