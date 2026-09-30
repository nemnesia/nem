package org.nem.specific.deploy;

import org.springframework.web.socket.server.RequestUpgradeStrategy;

/** Optional provider for servlet containers requiring explicit strategy selection. */
public interface NisWebSocketUpgradeStrategyProvider {
	/**
	 * Creates a strategy for the supplied servlet container, or returns {@code null} when
	 * this provider does not support it.
	 */
	RequestUpgradeStrategy createIfSupported(Object container);
}
