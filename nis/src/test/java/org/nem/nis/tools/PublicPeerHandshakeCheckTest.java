package org.nem.nis.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;
import org.nem.core.crypto.KeyPair;
import org.nem.core.node.Node;
import org.nem.core.node.NodeEndpoint;
import org.nem.core.node.NodeIdentity;
import org.nem.core.node.NodeMetaData;
import org.nem.core.serialization.JsonDeserializer;
import org.nem.core.serialization.JsonSerializer;
import org.nem.peer.node.AuthenticatedResponse;
import org.nem.peer.node.ImpersonatingPeerException;
import org.nem.peer.node.NodeChallenge;

public class PublicPeerHandshakeCheckTest {
	@Test
	public void verifiesAuthenticatedTestnetPeerResponse() throws Exception {
		final NodeIdentity identity = new NodeIdentity(new KeyPair(), "test peer");
		final HttpServer server = createPeer(identity, identity, -104);
		try {
			final Node peer = PublicPeerHandshakeCheck.verify("127.0.0.1", server.getAddress().getPort());
			assertEquals(-104, peer.getMetaData().getNetworkId());
		} finally {
			server.stop(0);
		}
	}

	@Test
	public void rejectsPeerWithInvalidChallengeSignature() throws Exception {
		final NodeIdentity identity = new NodeIdentity(new KeyPair(), "test peer");
		final NodeIdentity signer = new NodeIdentity(new KeyPair(), "different signer");
		final HttpServer server = createPeer(identity, signer, -104);
		try {
			final Exception exception = assertThrows(Exception.class,
					() -> PublicPeerHandshakeCheck.verify("127.0.0.1", server.getAddress().getPort()));
			Throwable cause = exception;
			while (null != cause && !(cause instanceof ImpersonatingPeerException)) {
				cause = cause.getCause();
			}
			assertTrue("expected an invalid peer signature to be rejected", cause instanceof ImpersonatingPeerException);
		} finally {
			server.stop(0);
		}
	}

	private static HttpServer createPeer(final NodeIdentity identity, final NodeIdentity responseSigner, final int networkId)
			throws Exception {
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		final NodeEndpoint endpoint = new NodeEndpoint("http", "127.0.0.1", server.getAddress().getPort());
		final Node node = new Node(identity, endpoint, new NodeMetaData("test", "NIS", null, networkId, 0));
		server.createContext("/node/info", exchange -> handleNodeInfo(exchange, node, responseSigner));
		server.start();
		return server;
	}

	private static void handleNodeInfo(final HttpExchange exchange, final Node node, final NodeIdentity responseSigner) {
		try (exchange) {
			final byte[] response;
			if ("GET".equals(exchange.getRequestMethod())) {
				response = JsonSerializer.serializeToBytes(node);
			} else {
				final String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				final JSONObject json = (JSONObject) JSONValue.parse(request);
				final NodeChallenge challenge = new NodeChallenge(new JsonDeserializer(json, null));
				response = JsonSerializer.serializeToBytes(new AuthenticatedResponse<>(node, responseSigner, challenge));
			}

			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}
}
