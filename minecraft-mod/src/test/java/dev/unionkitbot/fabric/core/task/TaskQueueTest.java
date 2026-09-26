package dev.unionkitbot.fabric.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Tests for the task queue: ordering, lifecycle transitions and persistence.
 */
class TaskQueueTest {

	private static TaskParameters params(String key, String value) {
		return TaskParameters.of(java.util.Map.of(key, value));
	}

	@Test
	void startsEmpty() {
		TaskQueue queue = new TaskQueue();
		assertEquals(0, queue.activeCount());
		assertEquals(0, queue.pendingCount());
		assertTrue(queue.peek().isEmpty());
		assertTrue(queue.poll().isEmpty());
	}

	@Test
	void submitsAndPeeksHighestPriorityFirst() {
		TaskQueue queue = new TaskQueue();
		AgentTask low = queue.submit(TaskKind.SCAN, TaskPriority.LOW, TaskParameters.empty()).orElseThrow();
		AgentTask critical = queue.submit(TaskKind.DELIVER, TaskPriority.CRITICAL, TaskParameters.empty())
				.orElseThrow();
		AgentTask normal = queue.submit(TaskKind.NAVIGATE, TaskPriority.NORMAL, TaskParameters.empty()).orElseThrow();

		assertEquals(critical.id(), queue.peek().orElseThrow().id());
		assertEquals(3, queue.pendingCount());
		assertEquals(3, queue.activeCount());
		assertNotNull(low.id());
		assertNotNull(normal.id());
	}

	@Test
	void pollRemovesTheTask() {
		TaskQueue queue = new TaskQueue();
		AgentTask task = queue.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty()).orElseThrow();
		Optional<AgentTask> polled = queue.poll();
		assertTrue(polled.isPresent());
		assertEquals(task.id(), polled.get().id());
		assertEquals(0, queue.pendingCount());
	}

	@Test
	void claimMovesATaskIntoRunning() {
		TaskQueue queue = new TaskQueue();
		AgentTask task = queue.submit(TaskKind.NAVIGATE, TaskPriority.NORMAL, params("target", "home")).orElseThrow();
		assertTrue(queue.claim(task.id(), "navigation"));
		assertEquals(TaskStatus.RUNNING, queue.find(task.id()).orElseThrow().status());
		assertEquals(Optional.of("navigation"), queue.find(task.id()).orElseThrow().owner());
		assertEquals(task.id(), queue.runningTask().orElseThrow().id());
		assertEquals(task.id(), queue.runningTaskOf("navigation").orElseThrow().id());
	}

	@Test
	void claimIsRejectedForUnknownOrAlreadyClaimedTasks() {
		TaskQueue queue = new TaskQueue();
		AgentTask task = queue.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty()).orElseThrow();
		assertTrue(queue.claim(task.id(), "scanning"));
		assertFalse(queue.claim(task.id(), "delivery"), "a running task cannot be claimed twice");
		assertFalse(queue.claim("does-not-exist", "scanning"));
	}

	@Test
	void completeMarksTheTaskTerminalAndFreesTheQueueSlot() {
		TaskQueue queue = new TaskQueue();
		AgentTask task = queue.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty()).orElseThrow();
		queue.claim(task.id(), "scanning");
		assertTrue(queue.complete(task.id(), "done"));
		assertEquals(0, queue.activeCount());
		assertEquals(1, queue.finishedCount());
		assertTrue(queue.runningTask().isEmpty());
		assertTrue(queue.find(task.id()).isEmpty(), "finished tasks leave the active index");
	}

	@Test
	void failMarksTheTaskTerminal() {
		TaskQueue queue = new TaskQueue();
		AgentTask task = queue.submit(TaskKind.DELIVER, TaskPriority.NORMAL, TaskParameters.empty()).orElseThrow();
		queue.claim(task.id(), "delivery");
		assertTrue(queue.fail(task.id(), "target left"));
		assertEquals("FAILED", queue.finishedSnapshots().get(0).status());
		assertEquals(0, queue.activeCount());
	}

	@Test
	void retryReturnsTheTaskToTheQueue() {
		TaskQueue queue = new TaskQueue();
		AgentTask task = queue.submit(TaskKind.NAVIGATE, TaskPriority.NORMAL, TaskParameters.empty()).orElseThrow();
		queue.claim(task.id(), "navigation");
		assertTrue(queue.retry(task.id(), "path blocked"));
		assertEquals(TaskStatus.RETRYING, queue.find(task.id()).orElseThrow().status());
		assertEquals(1, queue.pendingCount());
		assertEquals(1, queue.find(task.id()).orElseThrow().attempts());
		assertTrue(queue.runningTask().isEmpty());
	}

	@Test
	void cancelWorksForPendingAndRunningTasks() {
		TaskQueue queue = new TaskQueue();
		AgentTask pending = queue.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty()).orElseThrow();
		AgentTask running = queue.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty()).orElseThrow();
		queue.claim(running.id(), "scanning");

		assertTrue(queue.cancel(pending.id(), "changed my mind"));
		assertTrue(queue.cancel(running.id(), "changed my mind"));
		assertFalse(queue.cancel("missing", "no such task"));
		assertEquals(0, queue.activeCount());
		assertEquals(2, queue.finishedCount());
	}

	@Test
	void cancelAllReportsHowManyItCancelled() {
		TaskQueue queue = new TaskQueue();
		queue.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty());
		queue.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty());
		assertEquals(2, queue.cancelAll("shutdown"));
		assertEquals(0, queue.activeCount());
		assertEquals(0, queue.cancelAll("shutdown again"));
	}

	@Test
	void finishedHistoryIsBounded() {
		TaskQueue queue = new TaskQueue();
		int total = TaskQueue.FINISHED_HISTORY_LIMIT + 25;
		for (int i = 0; i < total; i++) {
			AgentTask task = queue.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty()).orElseThrow();
			queue.claim(task.id(), "scanning");
			queue.complete(task.id(), "iteration " + i);
		}
		assertEquals(TaskQueue.FINISHED_HISTORY_LIMIT, queue.finishedCount());
	}

	@Test
	void listenersSeeSubmissionAndStatusChanges() {
		TaskQueue queue = new TaskQueue();
		List<String> events = new ArrayList<>();
		queue.addListener(new TaskQueue.TaskQueueListener() {
			@Override
			public void onSubmitted(AgentTask task) {
				events.add("submitted:" + task.kind().id());
			}

			@Override
			public void onStatusChanged(AgentTask.Snapshot snapshot) {
				events.add("status:" + snapshot.status());
			}
		});
		AgentTask task = queue.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty()).orElseThrow();
		queue.claim(task.id(), "scanning");
		queue.complete(task.id(), "done");

		assertEquals(List.of("submitted:scan", "status:RUNNING", "status:COMPLETED"), events);
	}

	@Test
	void aFailingListenerDoesNotBreakTheQueue() {
		TaskQueue queue = new TaskQueue();
		queue.addListener(new TaskQueue.TaskQueueListener() {
			@Override
			public void onSubmitted(AgentTask task) {
				throw new IllegalStateException("listener is broken");
			}
		});
		List<String> seen = new ArrayList<>();
		queue.addListener(new TaskQueue.TaskQueueListener() {
			@Override
			public void onStatusChanged(AgentTask.Snapshot snapshot) {
				seen.add(snapshot.status());
			}
		});
		AgentTask task = queue.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty()).orElseThrow();
		queue.claim(task.id(), "scanning");
		assertEquals(List.of("RUNNING"), seen);
	}

	@Test
	void persistsAndRestoresActiveTasks() {
		TaskQueue queue = new TaskQueue();
		InMemoryStore store = new InMemoryStore();
		queue.setStore(store);
		AgentTask first = queue.submit(TaskKind.NAVIGATE, TaskPriority.HIGH, params("target", "base"))
				.orElseThrow();
		queue.submit(TaskKind.SCAN, TaskPriority.LOW, TaskParameters.empty());
		assertTrue(queue.persist());

		TaskQueue restored = new TaskQueue();
		restored.setStore(store);
		assertEquals(2, restored.restore());
		assertEquals(2, restored.pendingCount());
		Optional<AgentTask> reloaded = restored.find(first.id());
		assertTrue(reloaded.isPresent());
		assertEquals(TaskKind.NAVIGATE, reloaded.get().kind());
		assertEquals(TaskPriority.HIGH, reloaded.get().priority());
		assertEquals("base", reloaded.get().parameters().getString("target").orElse(null));
	}

	@Test
	void restoredTasksAreOrderedByPriorityAgain() {
		TaskQueue queue = new TaskQueue();
		InMemoryStore store = new InMemoryStore();
		queue.setStore(store);
		queue.submit(TaskKind.SCAN, TaskPriority.LOW, TaskParameters.empty());
		AgentTask critical = queue.submit(TaskKind.DELIVER, TaskPriority.CRITICAL, TaskParameters.empty())
				.orElseThrow();
		queue.persist();

		TaskQueue restored = new TaskQueue();
		restored.setStore(store);
		restored.restore();
		assertEquals(critical.id(), restored.peek().orElseThrow().id());
	}

	@Test
	void aFailingStoreIsReportedButNeverThrows() {
		TaskQueue queue = new TaskQueue();
		queue.setStore(new TaskStore() {
			@Override
			public boolean save(List<AgentTask.Snapshot> active, List<AgentTask.Snapshot> finished) {
				return false;
			}

			@Override
			public Payload load() {
				throw new IllegalStateException("disk exploded");
			}
		});
		queue.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty());
		assertFalse(queue.persist());
		assertEquals(0, queue.restore());
		assertTrue(queue.diagnostics().containsKey("lastPersistError"));
	}

	@Test
	void restoreSkipsCorruptEntries() {
		TaskQueue queue = new TaskQueue();
		queue.setStore(new TaskStore() {
			@Override
			public boolean save(List<AgentTask.Snapshot> active, List<AgentTask.Snapshot> finished) {
				return true;
			}

			@Override
			public Payload load() {
				return new Payload(TaskQueue.STORE_VERSION,
						List.of(
								new AgentTask.Snapshot("ok-1", "SCAN", "NORMAL", 2, "PENDING", null, null, 0,
										100L, 0L, 0L, 0.0d, java.util.Map.of(), List.of()),
								new AgentTask.Snapshot("bad-kind", "TELEPORT", "NORMAL", 2, "PENDING", null, null,
										0, 100L, 0L, 0L, 0.0d, java.util.Map.of(), List.of()),
								new AgentTask.Snapshot(null, "SCAN", "NORMAL", 2, "PENDING", null, null, 0, 100L,
										0L, 0L, 0.0d, java.util.Map.of(), List.of())),
						List.of());
			}
		});
		assertEquals(1, queue.restore());
		assertTrue(queue.find("ok-1").isPresent());
		assertTrue(queue.find("bad-kind").isEmpty());
	}

	@Test
	void noopStoreAcceptsEverything() {
		TaskQueue queue = new TaskQueue();
		queue.setStore(TaskStore.NOOP);
		queue.submit(TaskKind.SCAN, TaskPriority.NORMAL, TaskParameters.empty());
		assertTrue(queue.persist());
		assertEquals(0, queue.restore());
	}

	private static final class InMemoryStore implements TaskStore {
		private Payload payload = Payload.empty();

		@Override
		public boolean save(List<AgentTask.Snapshot> active, List<AgentTask.Snapshot> finished) {
			payload = new Payload(TaskQueue.STORE_VERSION, List.copyOf(active), List.copyOf(finished));
			return true;
		}

		@Override
		public Payload load() {
			return payload;
		}
	}
}
