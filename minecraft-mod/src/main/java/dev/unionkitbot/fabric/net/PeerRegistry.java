package dev.unionkitbot.fabric.net;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.unionkitbot.fabric.diag.DiagnosticsLog;
import dev.unionkitbot.fabric.net.ws.Peer;
import dev.unionkitbot.fabric.protocol.Envelope;
import dev.unionkitbot.fabric.protocol.MessageType;

/**
 * Tracks connected control peers and broadcasts frames to all of them.
 *
 * <p>Broadcasting is best-effort by design: the Discord bot may be offline, slow, or
 * mid-reconnect, and none of those may affect the Minecraft client. A peer whose
 * queue is full is counted and skipped rather than blocking the caller.
 */
public final class PeerRegistry {
	private static final String CATEGORY = "peers";

	private final CopyOnWriteArrayList<Peer> peers = new CopyOnWriteArrayList<>();
	private final DiagnosticsLog log;

	/**
	 * @param log the diagnostics log
	 */
	public PeerRegistry(DiagnosticsLog log) {
		this.log = log;
	}

	/**
	 * Registers a peer.
	 *
	 * @param peer the peer
	 */
	public void add(Peer peer) {
		if (peer == null) {
			return;
		}
		peers.add(peer);
		log.info(CATEGORY, "peer connected: " + peer.id() + " from " + peer.remoteAddress()
				+ " (" + peers.size() + " total)");
	}

	/**
	 * Removes a peer.
	 *
	 * @param peer the peer
	 */
	public void remove(Peer peer) {
		if (peers.remove(peer)) {
			log.info(CATEGORY, "peer disconnected: " + peer.id() + " (" + peers.size() + " remaining)");
		}
	}

	/**
	 * @return a snapshot of the connected peers
	 */
	public List<Peer> peers() {
		return List.copyOf(peers);
	}

	/**
	 * @return how many peers are connected
	 */
	public int size() {
		return peers.size();
	}

	/**
	 * @return {@code true} when at least one peer is connected
	 */
	public boolean hasPeers() {
		return !peers.isEmpty();
	}

	/**
	 * Sends a frame to every open peer.
	 *
	 * @param envelope the frame
	 * @return how many peers accepted the frame
	 */
	public int broadcast(Envelope envelope) {
		if (envelope == null) {
			return 0;
		}
		int delivered = 0;
		for (Peer peer : peers) {
			if (!peer.isOpen()) {
				continue;
			}
			try {
				if (peer.send(envelope)) {
					delivered++;
				} else {
					log.warn(CATEGORY, "dropped " + envelope.type().wireName() + " for peer " + peer.id());
				}
			} catch (RuntimeException e) {
				log.error(CATEGORY, "failed to send to peer " + peer.id(), e);
			}
		}
		return delivered;
	}

	/**
	 * Sends an event to every open peer.
	 *
	 * @param type the event type, which must be outbound
	 * @param payload the payload
	 * @return how many peers accepted the frame
	 */
	public int broadcast(MessageType type, com.google.gson.JsonObject payload) {
		return broadcast(Envelope.of(type, payload));
	}

	/**
	 * Closes every peer.
	 *
	 * @param code the WebSocket close code
	 * @param reason a short explanation
	 */
	public void closeAll(int code, String reason) {
		for (Peer peer : peers) {
			try {
				peer.close(code, reason);
			} catch (RuntimeException e) {
				log.error(CATEGORY, "failed to close peer " + peer.id(), e);
			}
		}
		peers.clear();
	}
}
