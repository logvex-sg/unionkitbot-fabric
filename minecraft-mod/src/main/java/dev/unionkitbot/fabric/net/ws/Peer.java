package dev.unionkitbot.fabric.net.ws;

import dev.unionkitbot.fabric.protocol.Envelope;

/**
 * A connected control peer, currently always the Discord bot.
 *
 * <p>Implementations must be safe to call from any thread and must never throw: a
 * peer that has gone away must simply drop the frame. The transport is deliberately
 * fire-and-forget, because a slow or dead Discord bot must never be able to stall
 * the Minecraft client thread.
 */
public interface Peer {

	/**
	 * @return a short identifier used in logs
	 */
	String id();

	/**
	 * @return the remote address, for diagnostics
	 */
	String remoteAddress();

	/**
	 * @return {@code true} while the transport is usable
	 */
	boolean isOpen();

	/**
	 * Queues a frame for delivery.
	 *
	 * @param envelope the frame
	 * @return {@code true} when the frame was handed to the transport
	 */
	boolean send(Envelope envelope);

	/**
	 * Closes the peer.
	 *
	 * @param code the WebSocket close code
	 * @param reason a short explanation
	 */
	void close(int code, String reason);

	/**
	 * @return the peer name reported during the handshake, or empty
	 */
	String peerName();

	/**
	 * @return when the peer was accepted, in epoch milliseconds
	 */
	long connectedAt();
}
