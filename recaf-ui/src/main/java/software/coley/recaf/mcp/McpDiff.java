package software.coley.recaf.mcp;

import jakarta.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal line based diff used to show users (and AI agents) exactly what an edit changed.
 * <p>
 * Implemented with a trimmed LCS so that it does not pull in any additional dependencies and
 * degrades gracefully on very large inputs.
 *
 * @author Recaf AI-MCP
 */
public final class McpDiff {
	/** Upper bound of LCS cells before falling back to a simple replace block. */
	private static final long MAX_LCS_CELLS = 2_000_000L;
	private static final int DEFAULT_CONTEXT = 3;

	private McpDiff() {
	}

	/**
	 * @param before
	 * 		Original text.
	 * @param after
	 * 		Updated text.
	 *
	 * @return Unified diff of the two texts, with a header describing added/removed line counts.
	 */
	@Nonnull
	public static Result diff(@Nonnull String before, @Nonnull String after) {
		return diff(before, after, DEFAULT_CONTEXT);
	}

	/**
	 * @param before
	 * 		Original text.
	 * @param after
	 * 		Updated text.
	 * @param context
	 * 		Number of unchanged context lines to keep around changes.
	 *
	 * @return Unified diff of the two texts.
	 */
	@Nonnull
	public static Result diff(@Nonnull String before, @Nonnull String after, int context) {
		String[] a = lines(before);
		String[] b = lines(after);

		// Fast path, nothing changed.
		if (before.equals(after))
			return new Result("", 0, 0);

		// Trim the common prefix/suffix so the LCS only sees the interesting middle.
		int maxPrefix = Math.min(a.length, b.length);
		int prefix = 0;
		while (prefix < maxPrefix && a[prefix].equals(b[prefix]))
			prefix++;

		int suffix = 0;
		while (suffix < maxPrefix - prefix &&
				a[a.length - 1 - suffix].equals(b[b.length - 1 - suffix]))
			suffix++;

		int middleA = a.length - prefix - suffix;
		int middleB = b.length - prefix - suffix;

		List<Op> ops = new ArrayList<>();
		for (int i = 0; i < prefix; i++)
			ops.add(new Op(' ', a[i]));
		appendMiddle(ops, a, prefix, middleA, b, prefix, middleB);
		for (int i = a.length - suffix; i < a.length; i++)
			ops.add(new Op(' ', a[i]));

		int added = 0;
		int removed = 0;
		for (Op op : ops) {
			if (op.type == '+') added++;
			else if (op.type == '-') removed++;
		}

		return new Result(render(ops, context), added, removed);
	}

	/**
	 * @param text
	 * 		Text to split.
	 *
	 * @return Lines of text, preserving trailing empty lines.
	 */
	@Nonnull
	private static String[] lines(@Nonnull String text) {
		String[] split = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
		return split;
	}

	private static void appendMiddle(@Nonnull List<Op> ops,
	                                 @Nonnull String[] a, int aStart, int aLength,
	                                 @Nonnull String[] b, int bStart, int bLength) {
		if (aLength == 0) {
			for (int i = 0; i < bLength; i++)
				ops.add(new Op('+', b[bStart + i]));
			return;
		}
		if (bLength == 0) {
			for (int i = 0; i < aLength; i++)
				ops.add(new Op('-', a[aStart + i]));
			return;
		}

		// Do not blow up the heap on massive inputs.
		if ((long) aLength * bLength > MAX_LCS_CELLS) {
			for (int i = 0; i < aLength; i++)
				ops.add(new Op('-', a[aStart + i]));
			for (int i = 0; i < bLength; i++)
				ops.add(new Op('+', b[bStart + i]));
			return;
		}

		int[][] lcs = new int[aLength + 1][bLength + 1];
		for (int i = aLength - 1; i >= 0; i--)
			for (int j = bLength - 1; j >= 0; j--)
				lcs[i][j] = a[aStart + i].equals(b[bStart + j])
						? lcs[i + 1][j + 1] + 1
						: Math.max(lcs[i + 1][j], lcs[i][j + 1]);

		int i = 0;
		int j = 0;
		while (i < aLength && j < bLength) {
			if (a[aStart + i].equals(b[bStart + j])) {
				ops.add(new Op(' ', a[aStart + i]));
				i++;
				j++;
			} else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
				ops.add(new Op('-', a[aStart + i]));
				i++;
			} else {
				ops.add(new Op('+', b[bStart + j]));
				j++;
			}
		}
		while (i < aLength)
			ops.add(new Op('-', a[aStart + i++]));
		while (j < bLength)
			ops.add(new Op('+', b[bStart + j++]));
	}

	@Nonnull
	private static String render(@Nonnull List<Op> ops, int context) {
		int size = ops.size();

		// Collect runs of interest.
		List<int[]> regions = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			if (ops.get(i).type == ' ') continue;
			int start = Math.max(0, i - context);
			int end = Math.min(size - 1, i + context);
			if (!regions.isEmpty() && start <= regions.get(regions.size() - 1)[1] + 1) {
				regions.get(regions.size() - 1)[1] = end;
			} else {
				regions.add(new int[]{start, end});
			}
		}

		StringBuilder sb = new StringBuilder();
		sb.append("--- before\n+++ after\n");
		for (int[] region : regions) {
			int start = region[0];
			int end = region[1];

			// Line numbers at the start of the region.
			int lineA = 1;
			int lineB = 1;
			for (int i = 0; i < start; i++) {
				char type = ops.get(i).type;
				if (type != '+') lineA++;
				if (type != '-') lineB++;
			}

			int countA = 0;
			int countB = 0;
			for (int i = start; i <= end; i++) {
				char type = ops.get(i).type;
				if (type != '+') countA++;
				if (type != '-') countB++;
			}

			sb.append("@@ -").append(lineA).append(',').append(countA)
					.append(" +").append(lineB).append(',').append(countB).append(" @@\n");
			for (int i = start; i <= end; i++) {
				Op op = ops.get(i);
				sb.append(op.type).append(op.line).append('\n');
			}
		}
		return sb.toString();
	}

	private record Op(char type, String line) {
	}

	/**
	 * Diff result.
	 *
	 * @param text
	 * 		Unified diff text. Empty when nothing changed.
	 * @param added
	 * 		Number of added lines.
	 * @param removed
	 * 		Number of removed lines.
	 */
	public record Result(@Nonnull String text, int added, int removed) {
		/**
		 * @return {@code true} when there is nothing to show.
		 */
		public boolean isEmpty() {
			return text.isEmpty();
		}
	}
}
