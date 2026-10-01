package org.nem.core.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Static class containing methods for creating a delayed future.
 */
public class SleepFuture {
	/**
	 * Creates a new future that fires at the specified time in the future.
	 *
	 * @param delay The delay (in milliseconds).
	 * @param <T> Any type.
	 * @return The future.
	 */
	public static <T> CompletableFuture<T> create(final int delay) {
		if (delay < 0) {
			throw new IllegalArgumentException("negative delay");
		}

		final CompletableFuture<T> future = new CompletableFuture<>();
		CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, Runnable::run).execute(() -> future.complete(null));
		return future;
	}
}
