package dev.unionkitbot.fabric.net.http;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.unionkitbot.fabric.net.ws.Peer;
import dev.unionkitbot.fabric.protocol.Envelope;

/**
 * A single-shot {@link Peer} used to service HTTP requests.
 *
 * <p>The control protocol is request/response shaped, and WebSocket is the
 * preferred transport. HTTP is offered as a fallback for tooling that cannot speak
 * WebSocket, so a request is answered by capturing the first frame the dispatcher
 * produces and returning it as the response body.
 */
public final class HttpPeer implements Peer {
	private static final String ID = "http";

	private final CompletableFuture<Envelope> response = new CompletableFuture<>();
	private final AtomicReference<Envelope> captured = new AtomicReference<>();
	private final AtomicBoolean open = new AtomicBoolean(true);
	private final String remoteAddress;
	private final long connectedAt = System.currentTimeMillis();

	/**
	 * @param remoteAddress the caller address, for diagnostics
	 */
	public HttpPeer(String remoteAddress) {
		this.remoteAddress = remoteAddress == null ? "unknown" : remoteAddress;
	}

	@Override
	public String id() {
		return ID;
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
	public boolean send(Envelope envelope) {
		if (envelope == null || !open.get()) {
			return false;
		}
		Envelope previous = captured.getAndSet(envelope);
		if (previous == null) {
			response.complete(envelope);
		}
		return true;
	}

	@Override
	public void close(int code, String reason) {
		open.set(false);
	}

	@Override
	public String peerName() {
		return "http-client";
	}

	@Override
	public long connectedAt() {
		return connectedAt;
	}

	/**
	 * Waits for the dispatcher's reply.
	 *
	 * @param timeoutMillis how long to wait
	 * @return the reply frame, or {@code null} when the dispatcher produced none
	 */
	public Envelope await(long timeoutMillis) {
		try {
			return response.get(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
			return null;
		}
	}
}
