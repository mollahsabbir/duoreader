package io.duoreader;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Named, independent queues; idle threads are released instead of retained for the process
 * lifetime.
 */
public final class TaskExecutors {
  public static ThreadPoolExecutor create(String name, int threads) {
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), factory(name));
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  public static ThreadFactory factory(String name) {
    AtomicInteger sequence = new AtomicInteger();
    return action ->
        new Thread(
            () -> {
              android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
              action.run();
            },
            name + "-" + sequence.incrementAndGet());
  }

  public static void cancel(Future<?> task, ThreadPoolExecutor executor) {
    if (task != null) task.cancel(true);
    executor.purge();
  }
}
