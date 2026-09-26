package dev.unionkitbot.fabric.net.http;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parses HTTP/1.1 request headers and body from a socket stream.
 *
 * <p>All limits are enforced before allocation: an oversized request line, header
 * block or body is rejected instead of being read into memory. That is what keeps a
 * hostile or buggy client from taking the game down.
 */
public final class HttpRequestReader {
	/** Maximum accepted request line length in bytes. */
	public static final int MAX_REQUEST_LINE = 8_192;

	/** Maximum accepted header block size in bytes. */
	public static final int MAX_HEADER_BYTES = 32_768;

	/** Maximum number of headers accepted. */
	public static final int MAX_HEADERS = 64;

	private HttpRequestReader() {
	}

	/**
	 * Raised when a request is malformed or exceeds a limit.
	 */
	public static final class BadRequestException extends IOException {
		private static final long serialVersionUID = 1L;

		private final int status;

		/**
		 * @param status the HTTP status to report
		 * @param message the reason
		 */
		public BadRequestException(int status, String message) {
			super(message);
			this.status = status;
		}

		/**
		 * @return the HTTP status to report
		 */
		public int status() {
			return status;
		}
	}

	/**
	 * Reads one request from the stream.
	 *
	 * @param rawStream the socket input stream
	 * @param maxBodyBytes the maximum accepted body size
	 * @return the parsed request
	 * @throws BadRequestException when the request is malformed or too large
	 * @throws IOException when the connection fails
	 */
	public static HttpRequest read(InputStream rawStream, int maxBodyBytes) throws IOException {
		BufferedInputStream in = rawStream instanceof BufferedInputStream buffered
				? buffered
				: new BufferedInputStream(rawStream, 8_192);

		String requestLine = readLine(in, MAX_REQUEST_LINE);
		if (requestLine == null) {
			throw new BadRequestException(400, "empty request");
		}
		String[] parts = requestLine.split(" ");
		if (parts.length != 3) {
			throw new BadRequestException(400, "malformed request line");
		}
		String method = parts[0].toUpperCase(Locale.ROOT);
		String target = parts[1];
		String version = parts[2];
		if (!version.startsWith("HTTP/1.")) {
			throw new BadRequestException(505, "unsupported HTTP version");
		}

		Map<String, String> headers = new LinkedHashMap<>();
		int headerBytes = 0;
		while (true) {
			String line = readLine(in, MAX_HEADER_BYTES);
			if (line == null) {
				throw new BadRequestException(400, "connection closed while reading headers");
			}
			if (line.isEmpty()) {
				break;
			}
			headerBytes += line.length() + 2;
			if (headerBytes > MAX_HEADER_BYTES) {
				throw new BadRequestException(431, "header block too large");
			}
			int colon = line.indexOf(':');
			if (colon <= 0) {
				throw new BadRequestException(400, "malformed header line");
			}
			if (headers.size() >= MAX_HEADERS) {
				throw new BadRequestException(431, "too many headers");
			}
			String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			String value = line.substring(colon + 1).trim();
			// Repeated headers are joined, which is what a proxy would do.
			headers.merge(name, value, (existing, additional) -> existing + ", " + additional);
		}

		String path = target;
		String rawQuery = null;
		int question = target.indexOf('?');
		if (question >= 0) {
			path = target.substring(0, question);
			rawQuery = target.substring(question + 1);
		}
		if (path.isEmpty()) {
			path = "/";
		}

		String body = readBody(in, headers, maxBodyBytes);
		return new HttpRequest(method, path, HttpRequest.parseQuery(rawQuery), headers, body);
	}

	private static String readBody(BufferedInputStream in, Map<String, String> headers, int maxBodyBytes)
			throws IOException {
		String transferEncoding = headers.get("transfer-encoding");
		if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
			return readChunkedBody(in, maxBodyBytes);
		}
		String contentLength = headers.get("content-length");
		if (contentLength == null) {
			return "";
		}
		long declared;
		try {
			declared = Long.parseLong(contentLength.trim());
		} catch (NumberFormatException e) {
			throw new BadRequestException(400, "invalid content-length");
		}
		if (declared < 0) {
			throw new BadRequestException(400, "negative content-length");
		}
		if (declared > maxBodyBytes) {
			throw new BadRequestException(413, "body exceeds " + maxBodyBytes + " bytes");
		}
		byte[] buffer = new byte[(int) declared];
		int read = 0;
		while (read < buffer.length) {
			int chunk = in.read(buffer, read, buffer.length - read);
			if (chunk < 0) {
				throw new BadRequestException(400, "body shorter than content-length");
			}
			read += chunk;
		}
		return new String(buffer, StandardCharsets.UTF_8);
	}

	private static String readChunkedBody(BufferedInputStream in, int maxBodyBytes) throws IOException {
		StringBuilder out = new StringBuilder();
		while (true) {
			String sizeLine = readLine(in, 64);
			if (sizeLine == null) {
				throw new BadRequestException(400, "truncated chunked body");
			}
			int semicolon = sizeLine.indexOf(';');
			String sizeText = (semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).trim();
			int size;
			try {
				size = Integer.parseInt(sizeText, 16);
			} catch (NumberFormatException e) {
				throw new BadRequestException(400, "invalid chunk size");
			}
			if (size < 0) {
				throw new BadRequestException(400, "negative chunk size");
			}
			if (size == 0) {
				// Consume trailers up to the terminating empty line.
				String trailer;
				do {
					trailer = readLine(in, MAX_HEADER_BYTES);
				} while (trailer != null && !trailer.isEmpty());
				return out.toString();
			}
			if (out.length() + size > maxBodyBytes) {
				throw new BadRequestException(413, "body exceeds " + maxBodyBytes + " bytes");
			}
			byte[] chunk = new byte[size];
			int read = 0;
			while (read < size) {
				int got = in.read(chunk, read, size - read);
				if (got < 0) {
					throw new BadRequestException(400, "truncated chunk");
				}
				read += got;
			}
			out.append(new String(chunk, StandardCharsets.UTF_8));
			readLine(in, 8);
		}
	}

	private static String readLine(BufferedInputStream in, int limit) throws IOException {
		StringBuilder builder = new StringBuilder(64);
		while (builder.length() <= limit) {
			int b = in.read();
			if (b < 0) {
				return builder.isEmpty() ? null : builder.toString();
			}
			if (b == '\n') {
				int length = builder.length();
				if (length > 0 && builder.charAt(length - 1) == '\r') {
					builder.setLength(length - 1);
				}
				return builder.toString();
			}
			builder.append((char) b);
		}
		throw new BadRequestException(414, "line exceeds " + limit + " bytes");
	}
}
