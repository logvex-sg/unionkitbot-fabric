package dev.unionkitbot.fabric.net.http;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A minimal HTTP/1.1 response writer.
 *
 * <p>Only what the control API needs: a status, a small header set and a JSON body.
 * Every response declares its length and closes the connection, which keeps the
 * server free of keep-alive state machines.
 *
 * @param status the HTTP status code
 * @param headers response headers
 * @param body the response body text
 */
public record HttpResponse(int status, Map<String, String> headers, String body) {

	public HttpResponse {
		headers = headers == null ? Map.of() : Map.copyOf(headers);
		body = body == null ? "" : body;
	}

	/**
	 * @param body the JSON body
	 * @return a {@code 200 OK} response
	 */
	public static HttpResponse ok(String body) {
		return json(200, body);
	}

	/**
	 * @param status the status code
	 * @param body the JSON body
	 * @return a JSON response
	 */
	public static HttpResponse json(int status, String body) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "application/json; charset=utf-8");
		headers.put("Cache-Control", "no-store");
		return new HttpResponse(status, headers, body);
	}

	/**
	 * @param status the status code
	 * @param message a short explanation
	 * @return a plain text response
	 */
	public static HttpResponse text(int status, String message) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "text/plain; charset=utf-8");
		headers.put("Cache-Control", "no-store");
		return new HttpResponse(status, headers, message == null ? "" : message);
	}

	/**
	 * @param status the status code
	 * @return a response with an empty body
	 */
	public static HttpResponse empty(int status) {
		return new HttpResponse(status, Map.of("Cache-Control", "no-store"), "");
	}

	/**
	 * Writes the response to a socket stream.
	 *
	 * @param rawStream the socket output stream
	 * @throws IOException when the connection fails
	 */
	public void write(OutputStream rawStream) throws IOException {
		BufferedOutputStream out = rawStream instanceof BufferedOutputStream buffered
				? buffered
				: new BufferedOutputStream(rawStream, 8_192);
		byte[] payload = body.getBytes(StandardCharsets.UTF_8);
		StringBuilder head = new StringBuilder(256);
		head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
		head.append("Content-Length: ").append(payload.length).append("\r\n");
		head.append("Connection: close\r\n");
		head.append("X-Content-Type-Options: nosniff\r\n");
		for (Map.Entry<String, String> header : headers.entrySet()) {
			head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
		}
		head.append("\r\n");
		out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
		if (payload.length > 0) {
			out.write(payload);
		}
		out.flush();
	}

	private static String reason(int status) {
		return switch (status) {
			case 200 -> "OK";
			case 202 -> "Accepted";
			case 204 -> "No Content";
			case 400 -> "Bad Request";
			case 401 -> "Unauthorized";
			case 403 -> "Forbidden";
			case 404 -> "Not Found";
			case 405 -> "Method Not Allowed";
			case 413 -> "Content Too Large";
			case 414 -> "URI Too Long";
			case 415 -> "Unsupported Media Type";
			case 429 -> "Too Many Requests";
			case 431 -> "Request Header Fields Too Large";
			case 500 -> "Internal Server Error";
			case 503 -> "Service Unavailable";
			case 505 -> "HTTP Version Not Supported";
			default -> "Status";
		};
	}
}
