package org.nem.specific.deploy;

import static org.junit.Assert.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.springframework.web.socket.sockjs.frame.AbstractSockJsMessageCodec;

public class NisWebAppWebsocketInitializerTest {

	@Test
	public void decodeInputStreamDecodesSockJsJsonEnvelopeAsUtf8Messages() throws Exception {
		// Arrange:
		final String stompFrame = "CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n雪\u0000";
		final String sockJsEnvelope = "[\"" + net.minidev.json.JSONValue.escape(stompFrame) + "\"]";
		final AbstractSockJsMessageCodec codec = NisWebAppWebsocketInitializer.createSockJsMessageCodec();

		// Act:
		final String[] decoded = codec.decodeInputStream(new ByteArrayInputStream(sockJsEnvelope.getBytes(StandardCharsets.UTF_8)));

		// Assert:
		assertArrayEquals(new String[] { stompFrame }, decoded);
	}

	@Test
	public void inputStreamAndStringDecodersUseTheSameSockJsEnvelopeSemantics() throws Exception {
		// Arrange:
		final String stompFrame = "SUBSCRIBE\nid:sub-1\ndestination:/topic/test\n\n\u0000";
		final String sockJsEnvelope = "[\"" + net.minidev.json.JSONValue.escape(stompFrame) + "\"]";
		final AbstractSockJsMessageCodec codec = NisWebAppWebsocketInitializer.createSockJsMessageCodec();

		// Act:
		final String[] decodedFromString = codec.decode(sockJsEnvelope);
		final String[] decodedFromStream = codec.decodeInputStream(new ByteArrayInputStream(sockJsEnvelope.getBytes(StandardCharsets.UTF_8)));

		// Assert:
		assertArrayEquals(decodedFromString, decodedFromStream);
	}
}
