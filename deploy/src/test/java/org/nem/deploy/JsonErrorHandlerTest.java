package org.nem.deploy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.nem.core.connect.ErrorResponse;
import org.nem.core.serialization.Deserializer;
import org.nem.core.serialization.JsonDeserializer;
import org.nem.core.time.TimeInstant;
import org.nem.core.time.TimeProvider;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;

public class JsonErrorHandlerTest {
	private static final TimeInstant CURRENT_TIME = new TimeInstant(84);

	@Test
	public void unsupportedMethodsAreNotHandledByTheErrorHandler() {
		final JsonErrorHandler handler = new JsonErrorHandler(timeProvider());
		Assert.assertFalse(handler.errorPageForMethod("DELETE"));
		Assert.assertTrue(handler.errorPageForMethod("GET"));
	}

	@Test
	public void responseHeadersAndBodyAreWrittenCorrectly() throws Exception {
		final TestContext context = new TestContext("badness", "foo");

		context.handle();

		Mockito.verify(context.response).setStatus(123);
		Mockito.verify(context.headers).put(org.eclipse.jetty.http.HttpHeader.CONTENT_TYPE, "application/json");
		Mockito.verify(context.headers).put(org.eclipse.jetty.http.HttpHeader.CACHE_CONTROL, "foo");
		final String body = context.body();
		Mockito.verify(context.headers).put(org.eclipse.jetty.http.HttpHeader.CONTENT_LENGTH, body.getBytes(StandardCharsets.ISO_8859_1).length);
		Assert.assertTrue(body.endsWith("\r\n"));
		final ErrorResponse error = context.errorResponse();
		Assert.assertEquals(CURRENT_TIME, error.getTimeStamp());
		Assert.assertEquals(123, error.getStatus());
		Assert.assertEquals("badness", error.getMessage());
	}

	@Test
	public void cacheControlIsOmittedWhenNotConfiguredAndMissingReasonStaysNull() throws Exception {
		final TestContext context = new TestContext(null, null);

		context.handle();

		Mockito.verify(context.headers, Mockito.never()).put(org.eclipse.jetty.http.HttpHeader.CACHE_CONTROL, "foo");
		Assert.assertNull(context.errorResponse().getMessage());
	}

	private static TimeProvider timeProvider() {
		final TimeProvider provider = Mockito.mock(TimeProvider.class);
		Mockito.when(provider.getCurrentTime()).thenReturn(CURRENT_TIME);
		return provider;
	}

	private static final class TestContext {
		private final ExposedJsonErrorHandler handler = new ExposedJsonErrorHandler(timeProvider());
		private final Request request = Mockito.mock(Request.class);
		private final Response response = Mockito.mock(Response.class);
		private final HttpFields.Mutable headers = Mockito.mock(HttpFields.Mutable.class);
		private final Callback callback = Mockito.mock(Callback.class);
		private final String reason;
		private byte[] content;

		private TestContext(final String reason, final String cacheControl) throws IOException {
			this.reason = reason;
			this.handler.setCacheControl(cacheControl);
			Mockito.when(this.response.getHeaders()).thenReturn(this.headers);
			Mockito.when(this.response.getStatus()).thenReturn(123);
			Mockito.doAnswer(invocation -> {
				final ByteBuffer buffer = invocation.getArgument(1);
				this.content = new byte[buffer.remaining()];
				buffer.get(this.content);
				return null;
			}).when(this.response).write(Mockito.eq(true), Mockito.any(ByteBuffer.class), Mockito.eq(this.callback));
		}

		private void handle() throws IOException {
			this.handler.generate(this.request, this.response, 123, this.reason, this.callback);
		}

		private String body() {
			return new String(this.content, StandardCharsets.ISO_8859_1);
		}

		private ErrorResponse errorResponse() {
			final Deserializer deserializer = new JsonDeserializer((JSONObject) JSONValue.parse(this.body()), null);
			return new ErrorResponse(deserializer);
		}
	}

	private static final class ExposedJsonErrorHandler extends JsonErrorHandler {
		private ExposedJsonErrorHandler(final TimeProvider provider) {
			super(provider);
		}

		private void generate(final Request request, final Response response, final int status, final String message, final Callback callback)
				throws IOException {
			super.generateResponse(request, response, status, message, null, callback);
		}
	}
}
