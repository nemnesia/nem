package org.nem.specific.deploy;

import jakarta.websocket.server.ServerContainer;
import org.springframework.web.socket.server.RequestUpgradeStrategy;

/** Selects the Spring Jakarta WebSocket bridge for Jetty 12's EE11 container. */
public class Jetty12WebSocketUpgradeStrategyProvider implements NisWebSocketUpgradeStrategyProvider {
	@Override
	public RequestUpgradeStrategy createIfSupported(final Object container) {
		return container instanceof ServerContainer ? new Jetty12WebSocketUpgradeStrategy() : null;
	}
}
