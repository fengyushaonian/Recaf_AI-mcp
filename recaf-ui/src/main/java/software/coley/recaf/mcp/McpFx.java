package software.coley.recaf.mcp;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import javafx.application.Platform;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.util.FxThreadUtil;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Small helper for running work on the JavaFX application thread from MCP worker threads.
 *
 * @author Recaf AI-MCP
 */
public final class McpFx {
	private static final Logger logger = Logging.get(McpFx.class);
	private static final long TIMEOUT_SECONDS = 30;

	private McpFx() {
	}

	/**
	 * @param runnable
	 * 		Action to run on the FX thread.
	 *
	 * @return {@code true} when the action ran without throwing.
	 */
	public static boolean runAndWait(@Nonnull Runnable runnable) {
		if (Platform.isFxApplicationThread()) {
			try {
				runnable.run();
				return true;
			} catch (Throwable t) {
				logger.error("Failed to run action on the FX thread", t);
				return false;
			}
		}

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<Throwable> error = new AtomicReference<>();
		try {
			FxThreadUtil.run(() -> {
				try {
					runnable.run();
				} catch (Throwable t) {
					error.set(t);
				} finally {
					latch.countDown();
				}
			});
		} catch (Throwable t) {
			logger.error("Failed to schedule work on the FX thread", t);
			return false;
		}

		try {
			if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
				return false;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
		if (error.get() != null) {
			logger.error("Failed to run action on the FX thread", error.get());
			return false;
		}
		return true;
	}

	/**
	 * @param callable
	 * 		Value producing action to run on the FX thread.
	 * @param fallback
	 * 		Value to return when the action fails.
	 * @param <T>
	 * 		Value type.
	 *
	 * @return Produced value, or the fallback.
	 */
	@Nullable
	public static <T> T callAndWait(@Nonnull Callable<T> callable, @Nullable T fallback) {
		if (Platform.isFxApplicationThread()) {
			try {
				return callable.call();
			} catch (Throwable t) {
				logger.error("Failed to compute value on the FX thread", t);
				return fallback;
			}
		}

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<T> value = new AtomicReference<>(fallback);
		try {
			FxThreadUtil.run(() -> {
				try {
					value.set(callable.call());
				} catch (Throwable t) {
					logger.error("Failed to compute value on the FX thread", t);
				} finally {
					latch.countDown();
				}
			});
		} catch (Throwable t) {
			logger.error("Failed to schedule work on the FX thread", t);
			return fallback;
		}

		try {
			if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
				return fallback;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return fallback;
		}
		return value.get();
	}
}
