package dev.unionkitbot.fabric.net.ws;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import dev.unionkitbot.fabric.protocol.Envelope;

/**
 * A server-side WebSocket connection.
 *
 * <p>Implements RFC 6455 closely enough for a control channel and nothing more:
 * text frames, ping/pong, close, and continuation frames. Frames are validated
 * before allocation, so a peer cannot make the client allocate an arbitrary buffer.
 *
 * <p>Outbound frames go through a bounded queue drained by a dedicated writer
 * thread. That is what keeps {@link #send(Envelope)} non-blocking: if the Discord
 * bot stops reading, the queue fills and further frames are dropped with a log line
 * rather than blocking the Minecraft client thread.
 */
public final class WebSocketSession implements Peer, AutoCloseable {
	/** The RFC 6455 handshake GUID. */
	private static final String HANDSHAKE_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	private static final int OPCODE_CONTINUATION = 0x0;
	private static final int OPCODE_TEXT = 0x1;
	private static final int OPCODE_BINARY = 0x2;
	private static final int OPCODE_CLOSE = 0x8;
	private static final int OPCODE_PING = 0x9;
	private static final int OPCODE_PONG = 0xA;

	/** Maximum accepted inbound frame payload in bytes. */
	public static final int MAX_FRAME_BYTES = 1_048_576;

	/** Maximum reassembled message size in bytes. */
	public static final int MAX_MESSAGE_BYTES = 2_097_152;

	private static final int OUTBOUND_QUEUE_CAPACITY = 256;

	private final Socket socket;
	private final InputStream in;
	private final OutputStream out;
	private final String id;
	private final String remoteAddress;
	private final long connectedAt;
	private final Consumer<String> onText;
	private final Consumer<WebSocketSession> onClosed;
	private final Consumer<Throwable> onError;

	private final BlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
	private final AtomicBoolean open = new AtomicBoolean(true);
	private final AtomicBoolean closing = new AtomicBoolean(false);
	private final AtomicBoolean writerStarted = new AtomicBoolean(false);
	private final AtomicBoolean readerStarted = new AtomicBoolean(false);

	private volatile String peerName = "unknown";
	private volatile long droppedFrames;
	private volatile String closeReason = "not closed";

	/**
	 * @param socket the accepted socket, already past the handshake
	 * @param handshake the validated handshake headers
	 * @param onText called for each complete text message
	 * @param onClosed called once when the connection ends
	 * @param onError called for transport errors
	 * @throws IOException when the streams cannot be obtained
	 */
	public WebSocketSession(Socket socket, WebSocketHandshake handshake, Consumer<String> onText,
			Consumer<WebSocketSession> onClosed, Consumer<Throwable> onError) throws IOException {
		this.socket = Objects.requireNonNull(socket, "socket");
		this.in = new BufferedInputStream(socket.getInputStream(), 8_192);
		this.out = new BufferedOutputStream(socket.getOutputStream(), 8_192);
		this.remoteAddress = String.valueOf(socket.getRemoteSocketAddress());
		this.id = Integer.toHexString(System.identityHashCode(this));
		this.connectedAt = System.currentTimeMillis();
		this.onText = Objects.requireNonNull(onText, "onText");
		this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
		this.onError = Objects.requireNonNull(onError, "onError");
		Objects.requireNonNull(handshake, "handshake");
	}

	/**
	 * Sets the peer name learned from the first {@code hello} frame.
	 *
	 * @param name the peer name
	 */
	public void setPeerName(String name) {
		if (name != null && !name.isBlank()) {
			this.peerName = name;
		}
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public String remoteAddress() {
		return remoteAddress;
	}

	@Override
	public boolean isOpen() {
		return open.get();
	}

	@Override
	public String peerName() {
		return peerName;
	}

	@Override
	public long connectedAt() {
		return connectedAt;
	}

	/**
	 * @return how many outbound frames were dropped because the peer was too slow
	 */
	public long droppedFrames() {
		return droppedFrames;
	}

	/**
	 * @return why the connection closed
	 */
	public String closeReason() {
		return closeReason;
	}

	/**
	 * Starts the reader and writer threads. Called once by the owning server.
	 */
	public void start() {
		if (readerStarted.compareAndSet(false, true)) {
			Thread reader = new Thread(this::readLoop, "unionkitbot-ws-reader-" + id);
			reader.setDaemon(true);
			reader.start();
		}
		if (writerStarted.compareAndSet(false, true)) {
			Thread writer = new Thread(this::writeLoop, "unionkitbot-ws-writer-" + id);
			writer.setDaemon(true);
			writer.start();
		}
	}

	@Override
	public boolean send(Envelope envelope) {
		if (envelope == null) {
			return false;
		}
		return sendText(envelope.encode());
	}

	/**
	 * Queues a text frame.
	 *
	 * @param text the frame payload
	 * @return {@code true} when the frame was queued
	 */
	public boolean sendText(String text) {
		if (text == null || !open.get()) {
			return false;
		}
		byte[] payload = text.getBytes(StandardCharsets.UTF_8);
		if (payload.length > MAX_FRAME_BYTES) {
			droppedFrames++;
			return false;
		}
		if (!outbound.offer(payload)) {
			droppedFrames++;
			return false;
		}
		return true;
	}

	private void writeLoop() {
		try {
			while (open.get()) {
				byte[] payload = outbound.poll(500, TimeUnit.MILLISECONDS);
				if (payload == null) {
					continue;
				}
				synchronized (out) {
					writeFrame(OPCODE_TEXT, payload);
					out.flush();
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			shutdown("writer interrupted");
		} catch (IOException e) {
			shutdown("write failed: " + e.getMessage());
		}
	}

	private void writeFrame(int opcode, byte[] payload) throws IOException {
		ByteArrayOutputStream header = new ByteArrayOutputStream(10);
		header.write(0x80 | opcode);
		int length = payload.length;
		if (length <= 125) {
			header.write(length);
		} else if (length <= 0xFFFF) {
			header.write(126);
			header.write((length >>> 8) & 0xFF);
			header.write(length & 0xFF);
		} else {
			header.write(127);
			for (int shift = 56; shift >= 0; shift -= 8) {
				header.write((int) (((long) length >>> shift) & 0xFF));
			}
		}
		out.write(header.toByteArray());
		out.write(payload);
	}

	private void readLoop() {
		try {
			while (open.get()) {
				if (!readFrame()) {
					break;
				}
			}
		} catch (EOFException e) {
			shutdown("peer closed the connection");
		} catch (IOException e) {
			shutdown("read failed: " + e.getMessage());
		} catch (RuntimeException e) {
			shutdown("protocol error: " + e.getMessage());
			onError.accept(e);
		} finally {
			closeQuietly();
			onClosed.accept(this);
		}
	}

	private boolean readFrame() throws IOException {
		int first = in.read();
		if (first < 0) {
			return false;
		}
		int second = in.read();
		if (second < 0) {
			throw new EOFException("truncated frame header");
		}
		boolean fin = (first & 0x80) != 0;
		int reserved = first & 0x70;
		int opcode = first & 0x0F;
		boolean masked = (second & 0x80) != 0;
		long length = second & 0x7F;
		if (reserved != 0) {
			throw new IOException("reserved frame bits set");
		}
		if (!masked) {
			// RFC 6455 requires every client-to-server frame to be masked.
			throw new IOException("unmasked client frame");
		}
		if (length == 126) {
			length = readUnsigned(2);
		} else if (length == 127) {
			length = readUnsigned(8);
		}
		if (length < 0 || length > MAX_FRAME_BYTES) {
			throw new IOException("frame exceeds " + MAX_FRAME_BYTES + " bytes");
		}
		byte[] mask = new byte[4];
		readFully(mask, 4);
		byte[] payload = new byte[(int) length];
		readFully(payload, payload.length);
		for (int i = 0; i < payload.length; i++) {
			payload[i] = (byte) (payload[i] ^ mask[i % 4]);
		}
		return handleFrame(fin, opcode, payload);
	}

	private boolean handleFrame(boolean fin, int opcode, byte[] payload) throws IOException {
		switch (opcode) {
			case OPCODE_CLOSE -> {
				int code = payload.length >= 2
						? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)
						: 1005;
				shutdown("peer sent close " + code);
				sendCloseFrame(1000, "closing");
				return false;
			}
			case OPCODE_PING -> {
				synchronized (out) {
					writeFrame(OPCODE_PONG, payload);
					out.flush();
				}
				return true;
			}
			case OPCODE_PONG -> {
				return true;
			}
			case OPCODE_TEXT, OPCODE_BINARY, OPCODE_CONTINUATION -> {
				// Fragmentation is handled by accumulating until FIN arrives.
				if (opcode != OPCODE_CONTINUATION) {
					fragmentOpcode = opcode;
					fragments.reset();
				}
				if (fragments.size() + payload.length > MAX_MESSAGE_BYTES) {
					throw new IOException("message exceeds " + MAX_MESSAGE_BYTES + " bytes");
				}
				fragments.write(payload);
				if (!fin) {
					return true;
				}
				int completeOpcode = fragmentOpcode;
				byte[] message = fragments.toByteArray();
				fragments.reset();
				if (completeOpcode != OPCODE_TEXT) {
					// The control protocol is JSON only; binary frames are ignored.
					return true;
				}
				deliver(new String(message, StandardCharsets.UTF_8));
				return true;
			}
			default -> throw new IOException("unsupported opcode " + opcode);
		}
	}

	private final ByteArrayOutputStream fragments = new ByteArrayOutputStream(256);
	private int fragmentOpcode = OPCODE_TEXT;

	private void deliver(String text) {
		try {
			onText.accept(text);
		} catch (RuntimeException e) {
			onError.accept(e);
		}
	}

	private long readUnsigned(int bytes) throws IOException {
		long value = 0L;
		for (int i = 0; i < bytes; i++) {
			int b = in.read();
			if (b < 0) {
				throw new EOFException("truncated frame length");
			}
			value = (value << 8) | (b & 0xFF);
		}
		if (bytes == 8 && value < 0) {
			throw new IOException("negative frame length");
		}
		return value;
	}

	private void readFully(byte[] buffer, int length) throws IOException {
		int read = 0;
		while (read < length) {
			int got = in.read(buffer, read, length - read);
			if (got < 0) {
				throw new EOFException("truncated frame payload");
			}
			read += got;
		}
	}

	@Override
	public void close(int code, String reason) {
		shutdown(reason == null ? "closed by server" : reason);
		sendCloseFrame(code, reason);
		closeQuietly();
	}

	private void sendCloseFrame(int code, String reason) {
		if (!closing.compareAndSet(false, true)) {
			return;
		}
		byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
		byte[] payload = new byte[2 + Math.min(reasonBytes.length, 100)];
		payload[0] = (byte) ((code >>> 8) & 0xFF);
		payload[1] = (byte) (code & 0xFF);
		System.arraycopy(reasonBytes, 0, payload, 2, payload.length - 2);
		try {
			synchronized (out) {
				writeFrame(OPCODE_CLOSE, payload);
				out.flush();
			}
		} catch (IOException e) {
			// The peer is already gone; nothing useful can be done.
			closeReason = closeReason + "; close frame failed: " + e.getMessage();
		}
	}

	private void shutdown(String reason) {
		closeReason = reason;
		open.set(false);
	}

	private void closeQuietly() {
		open.set(false);
		try {
			socket.close();
		} catch (IOException e) {
			closeReason = closeReason + "; socket close failed: " + e.getMessage();
		}
	}

	@Override
	public void close() {
		close(1000, "server shutdown");
	}

	/**
	 * Computes the {@code Sec-WebSocket-Accept} value for a client key.
	 *
	 * @param clientKey the {@code Sec-WebSocket-Key} header value
	 * @return the accept token
	 */
	public static String acceptToken(String clientKey) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			byte[] hash = digest.digest((clientKey + HANDSHAKE_GUID).getBytes(StandardCharsets.US_ASCII));
			return Base64.getEncoder().encodeToString(hash);
		} catch (NoSuchAlgorithmException e) {
			// SHA-1 is mandated by RFC 6455 and is always present on a JDK.
			throw new IllegalStateException("SHA-1 is unavailable", e);
		}
	}

	/**
	 * @return a copy of the outbound queue depth, for diagnostics
	 */
	public int outboundDepth() {
		return outbound.size();
	}

	/**
	 * @return the local port the peer is connected to
	 */
	public int localPort() {
		return socket.getLocalPort();
	}

	/**
	 * @return whether the underlying socket has been closed
	 */
	public boolean isSocketClosed() {
		return socket.isClosed();
	}

	@Override
	public String toString() {
		return "WebSocketSession[" + id + " " + remoteAddress + " peer=" + peerName + " open=" + open.get()
				+ " dropped=" + droppedFrames + " queue=" + outbound.size() + "]";
	}

	/**
	 * Convenience for tests: exposes the mask helper used during decoding.
	 *
	 * @param payload the payload to unmask in place
	 * @param mask the four byte mask
	 * @return the same array, unmasked
	 */
	static byte[] unmask(byte[] payload, byte[] mask) {
		byte[] copy = Arrays.copyOf(payload, payload.length);
		for (int i = 0; i < copy.length; i++) {
			copy[i] = (byte) (copy[i] ^ mask[i % 4]);
		}
		return copy;
	}
}
