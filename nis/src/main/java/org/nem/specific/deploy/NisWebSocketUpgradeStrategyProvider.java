package org.nem.specific.deploy;

import org.springframework.web.socket.server.RequestUpgradeStrategy;

/** Optional provider for servlet containers Spring 5.3 does not detect automatically. */
public interface NisWebSocketUpgradeStrategyProvider {
	/**
	 * Creates a strategy for the supplied servlet container, or returns {@code null} when
	 * this provider does not support it.
	 */
	RequestUpgradeStrategy createIfSupported(Object container);
}
