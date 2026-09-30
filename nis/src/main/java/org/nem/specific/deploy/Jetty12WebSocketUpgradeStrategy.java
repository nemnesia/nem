package org.nem.specific.deploy;

import org.springframework.web.socket.server.standard.StandardWebSocketUpgradeStrategy;

/** Uses Spring 7's Jakarta WebSocket strategy for Jetty 12 EE11. */
public class Jetty12WebSocketUpgradeStrategy extends StandardWebSocketUpgradeStrategy {
}
