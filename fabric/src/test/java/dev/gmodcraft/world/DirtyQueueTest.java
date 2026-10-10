package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** P6e: sections marked from world generation threads while the server thread drains. */
class DirtyQueueTest {
	@Test
	void marksFromManyThreadsWhileDrainingAreAllSentAndNothingBreaks() throws Exception {
		DirtyQueue q = new DirtyQueue();
		q.bindOwner(Thread.currentThread());
		TickBudget budget = new TickBudget(200_000L, System::nanoTime); // tiny budget: many partial drains
		int threads = 8, perThread = 50_000;
		LongOpenHashSet sent = new LongOpenHashSet();
		AtomicReference<Throwable> failed = new AtomicReference<>();
		CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(threads);
		List<Thread> workers = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			int id = t;
			Thread w = new Thread(() -> {
				try {
					go.await();
					for (int i = 0; i < perThread; i++) {
						q.mark((long) id * 1_000_000L + i % 20_000); // repeats: the set dedups them
					}
				} catch (Throwable e) {
					failed.set(e);
				} finally {
					done.countDown();
				}
			}, "Worker-Main-" + t);
			workers.add(w);
			w.start();
		}
		go.countDown();
		long marksOnOwner = 0;
		while (done.getCount() > 0) {
			q.mark(-1 - (marksOnOwner++ % 1000)); // the owner marks too (CHUNK_LOAD, block updates)
			budget.drain(q.absorb(), key -> sent.add(key) || true, b -> {
			});
		}
		for (Thread w : workers) {
			w.join();
		}
		while (budget.drain(q.absorb(), key -> sent.add(key) || true, b -> {
		}) != TickBudget.Stop.EMPTY) {
			// finish the backlog
		}
		assertEquals(null, failed.get());
		assertEquals(0, q.size());
		for (int t = 0; t < threads; t++) {
			for (int i = 0; i < 20_000; i++) {
				assertTrue(sent.contains((long) t * 1_000_000L + i), "lost mark " + t + "/" + i);
			}
		}
		assertTrue(sent.contains(-1L));
	}

	@Test
	void marksBeforeAnyOwnerAreKeptAndDeduped() throws Exception {
		DirtyQueue q = new DirtyQueue(); // no bindOwner yet: every thread is "another thread"
		int threads = 6;
		List<Thread> workers = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			int id = t;
			Thread w = new Thread(() -> {
				for (int i = 0; i < 1000; i++) {
					q.mark(i % 100);              // the same sections from every thread
					q.mark(10_000L * (id + 1) + i % 50); // and some of its own
				}
			});
			workers.add(w);
			w.start();
		}
		for (Thread w : workers) {
			w.join();
		}
		int distinct = 100 + threads * 50;
		assertEquals(distinct, q.pendingOffThread());
		q.bindOwner(Thread.currentThread());
		LongOpenHashSet got = new LongOpenHashSet(q.absorb());
		assertEquals(distinct, q.size());
		assertEquals(0, q.pendingOffThread());
		for (int i = 0; i < 100; i++) {
			assertTrue(got.contains(i));
		}
		for (int t = 0; t < threads; t++) {
			for (int i = 0; i < 50; i++) {
				assertTrue(got.contains(10_000L * (t + 1) + i));
			}
		}
	}

	@Test
	void theOffThreadStoreStaysBoundedWithoutAbsorbing() throws Exception {
		DirtyQueue q = new DirtyQueue();
		q.bindOwner(new Thread(() -> {
		})); // an owner that never ticks (a dead thread after a restart, or no link)
		List<Thread> workers = new ArrayList<>();
		for (int t = 0; t < 4; t++) {
			Thread w = new Thread(() -> {
				for (int round = 0; round < 200; round++) {
					for (int i = 0; i < 500; i++) {
						q.mark(i);
					}
				}
			});
			workers.add(w);
			w.start();
		}
		for (Thread w : workers) {
			w.join();
		}
		assertEquals(500, q.pendingOffThread()); // 400 000 marks, 500 sections
	}

	@Test
	void clearDropsQueuedMarksFromOtherThreads() throws Exception {
		DirtyQueue q = new DirtyQueue();
		q.bindOwner(Thread.currentThread());
		Thread w = new Thread(() -> q.mark(42L));
		w.start();
		w.join();
		q.mark(7L);
		q.clear();
		assertEquals(0, q.absorb().size());
	}
}
