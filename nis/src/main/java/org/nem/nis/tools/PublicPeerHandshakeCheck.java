package org.nem.nis.tools;

import java.net.URL;
import java.util.concurrent.TimeUnit;
import org.nem.core.connect.HttpJsonResponseStrategy;
import org.nem.core.connect.HttpMethodClient;
import org.nem.core.node.Node;
import org.nem.core.node.NodeEndpoint;
import org.nem.core.serialization.Deserializer;
import org.nem.peer.connect.CommunicationMode;
import org.nem.peer.connect.HttpCommunicator;
import org.nem.nis.connect.HttpConnector;

/** Validates a public NIS endpoint with the authenticated peer information exchange. */
public final class PublicPeerHandshakeCheck {
	private static final int TESTNET_NETWORK_ID = -104;

	private PublicPeerHandshakeCheck() {
	}

	/**
	 * Checks the endpoint by fetching its public identity and verifying a fresh signed peer response.
	 *
	 * @param args Host and optional HTTP port.
	 * @throws Exception if the endpoint cannot be authenticated as a Testnet peer.
	 */
	public static void main(final String[] args) throws Exception {
		if (args.length < 1 || args.length > 2) {
			throw new IllegalArgumentException("usage: PublicPeerHandshakeCheck HOST [PORT]");
		}

		final String host = args[0];
		final int port = args.length == 2 ? Integer.parseInt(args[1]) : 7890;
		final Node peer = verify(host, port);
		System.out.println("advertised endpoint: " + peer.getEndpoint());
		System.out.println("network ID: " + peer.getMetaData().getNetworkId());
		System.out.println("authenticated NIS peer handshake: verified");
	}

	static Node verify(final String host, final int port) throws Exception {
		final NodeEndpoint target = new NodeEndpoint("http", host, port);
		final URL infoUrl = URL.of(target.getBaseUrl().toURI().resolve("node/info"), null);
		try (HttpMethodClient<Deserializer> client = new HttpMethodClient<>(5000, 10000, 15000)) {
			final Node advertisedPeer = new Node(client.get(infoUrl, new HttpJsonResponseStrategy(null)).get());
			if (TESTNET_NETWORK_ID != advertisedPeer.getMetaData().getNetworkId()) {
				throw new IllegalStateException("NIS endpoint is not on Testnet (expected network ID -104)");
			}
			if (!target.equals(advertisedPeer.getEndpoint())) {
				throw new IllegalStateException("advertised NIS endpoint does not match the externally checked endpoint");
			}

			final Node targetPeer = new Node(advertisedPeer.getIdentity(), target, advertisedPeer.getMetaData());
			final HttpConnector connector = new HttpConnector(new HttpCommunicator(client, CommunicationMode.JSON, null));
			final Node authenticatedPeer = connector.getInfo(targetPeer).get(15, TimeUnit.SECONDS);
			if (!advertisedPeer.getIdentity().equals(authenticatedPeer.getIdentity())) {
				throw new IllegalStateException("authenticated NIS peer identity changed during the handshake");
			}
			if (TESTNET_NETWORK_ID != authenticatedPeer.getMetaData().getNetworkId() || !target.equals(authenticatedPeer.getEndpoint())) {
				throw new IllegalStateException("authenticated NIS peer returned unexpected endpoint or network metadata");
			}
			return authenticatedPeer;
		}
	}
}
