package software.coley.recaf.mcp;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Result of a single MCP tool invocation.
 *
 * @param text  Text payload returned to the client.
 * @param error {@code true} when the invocation failed.
 *
 * @author Recaf AI-MCP
 */
public record McpToolResult(@Nonnull String text, boolean error) {
	/**
	 * @param text
	 * 		Payload text.
	 *
	 * @return Successful result.
	 */
	@Nonnull
	public static McpToolResult ok(@Nonnull String text) {
		return new McpToolResult(text, false);
	}

	/**
	 * @param text
	 * 		Payload text.
	 *
	 * @return Failed result.
	 */
	@Nonnull
	public static McpToolResult fail(@Nonnull String text) {
		return new McpToolResult(text, true);
	}

	/**
	 * @param message
	 * 		Message to wrap.
	 *
	 * @return Failed result with the given message.
	 */
	@Nonnull
	public static McpToolResult fail(@Nullable Throwable message) {
		return fail(message == null ? "Unknown error" : message.toString());
	}
}
