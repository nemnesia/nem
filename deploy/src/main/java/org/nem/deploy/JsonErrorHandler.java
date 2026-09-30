package org.nem.deploy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.eclipse.jetty.http.*;
import org.eclipse.jetty.ee11.servlet.ErrorHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.nem.core.connect.ErrorResponse;
import org.nem.core.serialization.JsonSerializer;
import org.nem.core.time.TimeProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Custom error handler that returns JSON error responses in the same format as the ExceptionControllerAdvice.
 */
public class JsonErrorHandler extends ErrorHandler {
	private final TimeProvider timeProvider;

	/**
	 * Creates a new JSON error handler.
	 *
	 * @param timeProvider The time provider.
	 */
	@Autowired(required = true)
	public JsonErrorHandler(final TimeProvider timeProvider) {
		this.timeProvider = timeProvider;
	}

	@Override
	protected void generateResponse(final Request request, final Response response, final int code, final String message,
			final Throwable cause, final Callback callback) throws IOException {
		final ErrorResponse errorResponse = new ErrorResponse(this.timeProvider.getCurrentTime(), message, code);
		final byte[] content = (JsonSerializer.serializeToJson(errorResponse).toJSONString() + "\r\n")
				.getBytes(StandardCharsets.ISO_8859_1);
		response.setStatus(code);
		response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString());
		response.getHeaders().put(HttpHeader.CONTENT_LENGTH, content.length);
		if (null != this.getCacheControl()) {
			response.getHeaders().put(HttpHeader.CACHE_CONTROL, this.getCacheControl());
		}
		response.write(true, ByteBuffer.wrap(content), callback);
	}
}
