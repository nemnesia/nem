package org.nem.core.connect;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

public class WireMockCompatibilityTest {
	@Test
	public void dynamicPortStubMatchingResponseAndRequestVerificationWork() throws IOException {
		final WireMockServer server = new WireMockServer(options().dynamicPort());
		server.start();
		try {
			server.stubFor(get(urlPathEqualTo("/lookup"))
					.withQueryParam("network", equalTo("testnet"))
					.withHeader("X-Nem-Test", equalTo("core"))
					.willReturn(aResponse().withStatus(202).withHeader("X-Result", "accepted").withBody("matched")));

			MatcherAssert.assertThat(server.port() > 0, IsEqual.equalTo(true));
			final URL url = new URL(server.baseUrl() + "/lookup?network=testnet");
			final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setConnectTimeout(5000);
			connection.setReadTimeout(5000);
			connection.setRequestProperty("X-Nem-Test", "core");
			try {
				MatcherAssert.assertThat(connection.getResponseCode(), IsEqual.equalTo(202));
				MatcherAssert.assertThat(connection.getHeaderField("X-Result"), IsEqual.equalTo("accepted"));
				MatcherAssert.assertThat(new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
						IsEqual.equalTo("matched"));
			} finally {
				connection.disconnect();
			}

			server.verify(1, getRequestedFor(urlPathEqualTo("/lookup"))
					.withQueryParam("network", equalTo("testnet"))
					.withHeader("X-Nem-Test", equalTo("core")));
		} finally {
			server.stop();
		}

		MatcherAssert.assertThat(server.isRunning(), IsEqual.equalTo(false));
	}
}
