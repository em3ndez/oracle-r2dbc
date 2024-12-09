/*
  Copyright (c) 2020, 2022, Oracle and/or its affiliates.

  This software is dual-licensed to you under the Universal Permissive License
  (UPL) 1.0 as shown at https://oss.oracle.com/licenses/upl or Apache License
  2.0 as shown at http://www.apache.org/licenses/LICENSE-2.0. You may choose
  either license.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

     https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package oracle.r2dbc.impl;

import oracle.r2dbc.impl.OracleR2dbcExceptions.JdbcRunnable;
import oracle.r2dbc.impl.OracleR2dbcExceptions.JdbcSupplier;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;


/**
 * Concrete implementation of {@link AsyncLock} for use with 21.x releases of
 * Oracle JDBC.
 */
final class AsyncLockImpl implements AsyncLock {

  /**
   * Count that is incremented for invocation of {@link #lock(Runnable)}, and is
   * decremented by each invocation of {@link #unlock()}. This lock is unlocked
   * when the count is 0.
   */
  private final AtomicInteger waitCount = new AtomicInteger(0);

  /**
   * Dequeue of {@code Runnable} callbacks enqueued each time an invocation of
   * {@link #lock(Runnable)} is not able to acquire this lock. The head of this
   * dequeue is dequeued and executed by an invocation of {@link #unlock()}.
   */
  private final ConcurrentLinkedDeque<Runnable> waitQueue =
    new ConcurrentLinkedDeque<>();

  @Override
  public void lock(Runnable callback) {
    assert waitCount.get() >= 0 : "Wait count is less than 0: " + waitCount;

    // Acquire this lock and invoke the callback immediately, if possible
    if (waitCount.compareAndSet(0, 1)) {
      callback.run();
    }
    else {
      // Enqueue the callback to be invoked asynchronously when this
      // lock is unlocked
      waitQueue.addLast(callback);

      // Another thread may have unlocked this lock while this thread was
      // enqueueing the callback. Dequeue and execute the head of the deque
      // if this is the case.
      if (0 == waitCount.getAndIncrement())
        waitQueue.removeFirst().run();
    }
  }

  private void unlock() {
    assert waitCount.get() > 0 : "Wait count is less than 1: " + waitCount;

    // Decrement the count. Assuming that lock was called before this
    // method, the count is guaranteed to be 1 or greater. If it greater
    // than 1 after being decremented, then another invocation of lock has
    // enqueued a callback
    if (0 != waitCount.decrementAndGet())
      waitQueue.removeFirst().run();
  }

  @Override
  public Publisher<Void> run(JdbcRunnable jdbcRunnable) {
    return Mono.create(monoSink ->
      lock(() -> {
        try {
          jdbcRunnable.runOrThrow();
          unlock();
          monoSink.success();
        }
        catch (Throwable throwable) {
          unlock();
          monoSink.error(throwable);
        }
      }));
  }

  @Override
  public <T> Publisher<T> get(JdbcSupplier<T> jdbcSupplier) {
    return Mono.create(monoSink ->
      lock(() -> {
        try {
          T result = jdbcSupplier.getOrThrow();
          unlock();
          monoSink.success(result);
        }
        catch (Throwable throwable) {
          unlock();
          monoSink.error(throwable);
        }
      }));
  }

  @Override
  public <T> Publisher<T> flatMap(JdbcSupplier<Publisher<T>> publisherSupplier) {
    return Mono.from(get(publisherSupplier))
      .flatMapMany(Function.identity());
  }

  @Override
  public <T> Publisher<T> lock(Publisher<T> publisher) {
    return subscriber ->
      lock(() ->
        publisher.subscribe(new UsingConnectionSubscriber<>(subscriber)));
  }

  /**
   * <p>
   * A {@code Subscriber} that uses this {@link AsyncLockImpl} to ensure that
   * threads do not become blocked when contending for this adapter's JDBC
   * {@code Connection}. Any time a {@code Subscriber} subscribes to a
   * {@code Publisher} that uses the JDBC {@code Connection}, an instance of
   * {@code UsingConnectionSubscriber} should be created in order to proxy
   * signals between that {@code Publisher} and the downstream
   * {@code Subscriber}.
   * </p>
   *
   * <h3>Problem Overview</h3>
   * <p>
   * {@code UsingConnectionSubscriber} solves a problem with how Oracle JDBC
   * implements thread safety. When an asynchronous database call is initiated
   * with a {@code Connection}, that {@code Connection} is locked until
   * the call completes. When a {@code Connection} is locked, any thread that
   * invokes a method of that {@code Connection} or any object created by that
   * {@code Connection} will become blocked. This can lead to a deadlock where
   * all threads in a pool have become blocked until the database call
   * completes, and JDBC can not complete the database call until a thread
   * becomes unblocked.
   * </p><p>
   * As a simplified example, consider what would happen with the code below if
   * the Executor had a pool of 1 thread:
   * </p><pre>
   * List<Flow.Publisher<Boolean>> publishers = new ArrayList<>();
   * executor.execute(() -> {
   *   try {
   *     publishers.add(connection.prepareStatement("SELECT 0 FROM dual")
   *       .unwrap(OraclePreparedStatement.class)
   *       .executeAsyncOracle());
   *
   *     publishers.add(connection.prepareStatement("SELECT 1 FROM dual")
   *       .unwrap(OraclePreparedStatement.class)
   *       .executeAsyncOracle());
   *   }
   *   catch (SQLException sqlException) {
   *     sqlException.printStackTrace();
   *   }
   * });
   * </pre><p>
   * After the first call to {@code executeAsyncOracle}, the connection is
   * locked, and so when the second call to {@code executeAsyncOracle} is
   * made, the executor thread is blocked. If Oracle JDBC is configured to use
   * this same executor, which has a pool of just one thread, then no thread
   * is left to handle the response from the database for the first call to
   * {@code executeAsyncOracle}. With no thread available to handle the
   * response, the call is never completed and the connection is never
   * unlocked, so the code above results in a deadlock.
   * </p><p>
   * While the code above presents a somewhat obvious scenario, it is more
   * common for deadlocks to occur in less obvious ways. Consider this code
   * example which uses Project Reactor and R2DBC:
   * </p><pre>
   * Flux.usingWhen(
   *   connectionFactory.create(),
   *   connection ->
   *     Flux.usingWhen(
   *       Mono.from(connection.beginTransaction())
   *         .thenReturn(connection),
   *       connection ->
   *         connection.createStatement("INSERT INTO deadlock VALUES(?)")
   *           .bind(0, 0)
   *           .execute(),
   *       Connection::commitTransaction),
   *   Connection::close)
   *   .hasElements();
   * </pre><p>
   * The hasElements() operator transforms the sequence into a single boolean
   * value. When an {@code onNext} signal delivers this value, the subscriber
   * emits a {@code cancel} signal to the upstream publisher as the
   * subscriber does not require any additional values. This cancel signal
   * triggers a subscription to both the commitTransaction() publisher and to
   * the close() publisher. The commitTransaction() publisher subscribed to
   * first, and this has the Oracle JDBC connection locked until that
   * database call completes. The close() publisher is subscribed to immediately
   * afterwards, and this has the thread become blocked. As there is no
   * thread left to handle the result of the commit, the connection never
   * becomes unlocked.
   * </p>
   *
   * <h3>Guarding Access to the JDBC Connection</h3>
   * <p>
   * Access to the JDBC Connection must be guarded such that no thread will
   * attempt to use it when an asynchronous database call is in-flight. The
   * potential for an in-flight call exists whenever there is a pending signal
   * from the upstream {@code Publisher}. Instances of
   * {@code UsingConnectionSubscriber} acquire this {@link AsyncLockImpl}
   * before requesting a signal from the publisher, and release the
   * {@code asyncLock} once that signal is received. This ensures that no other
   * thread will be able to acquire the {@code asyncLock} when a pending signal
   * is potentially pending upon an asynchronous database call.
   * </p><p>
   * An {@code onSubscribe} signal is pending between an invocation of
   * {@link Publisher#subscribe(Subscriber)} and an invocation of
   * {@link Subscriber#onSubscribe(Subscription)}. Accordingly, the
   * {@link AsyncLockImpl} <i>MUST</i> be acquired before invoking
   * {@code subscribe} with an instance of {@code UsingConnectionSubscriber}.
   * When that instance receives an {@code onSubscribe} signal, it will release
   * the {@code asyncLock}.
   * </p><p>
   * An {@code onNext} signal is pending between an invocation of
   * {@link Subscription#request(long)} and a number of invocations of
   * {@link Subscriber#onNext(Object)} equal to the number of
   * values requested. Accordingly, instances of
   * {@code UsingConnectionSubscriber} acquire the {@link AsyncLockImpl} before
   * emitting a {@code request} signal, and release the {@code asyncLock} when
   * a corresponding number of {@code onNext} signals have been received.
   * </p><p>
   * When a {@code cancel} signal is emitted to the upstream {@code Publisher},
   * that publisher will not emit any further signals to the downstream
   * {@code Subscriber}. If an instance {@code UsingConnectionSubscriber}
   * has acquired the {@link AsyncLockImpl} for a pending {@code onNext} signal,
   * then it will defer sending a {@code cancel} signal until the pending
   * {@code onNext} signal has been received. Deferring cancellation until the
   * the publisher invokes {@code onNext} ensures that the cancellation happens
   * after any pending database call, and before any subsequent database calls
   * that would obtain additional values for {@code onNext}.
   * </p>
   */
  private final class UsingConnectionSubscriber<T>
    implements Subscription, Subscriber<T> {

    /**
     * Value of {@link #demand} after a {@code cancel} signal has been received
     * from the downstream subscriber, but before a pending {@code onNext}
     * signal has been received.
     */
    private static final long CANCEL_PENDING = -1;

    /**
     * Value of {@link #demand} after a {@code cancel} signal has been received
     * from the downstream subscriber, and after any pending {@code onNext}
     * signal has been received, and after the {@code cancel} signal has been
     * emitted to the upstream publisher.
     */
    private static final long TERMINATED = -2;

    /** Downstream subscriber that requests values from database calls. */
    private final Subscriber<T> downstream;

    /**
     * Subscription from an upstream publisher that emits values from database
     * calls.
     */
    private Subscription upstream;

    /**
     * Unfilled demand from {@code request} signals. When the value is a
     * positive number, it is equal to the number of pending {@code onNext}
     * signals. When a {@code cancel} signal is received from downstream, the
     * value is set to either {@link #CANCEL_PENDING} or
     * {@link #TERMINATED}.
     * if an {@code
     * onNext}
     * signal is
     * pending, or it is set to {@link #TERMINATED} if no {@code onNext}
     * signal is pending.
     *
     */
    private final AtomicLong demand = new AtomicLong(0L);

    private UsingConnectionSubscriber(Subscriber<T> downstream) {
      this.downstream = downstream;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      unlock();
      upstream = subscription;
      downstream.onSubscribe(this);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Acquires the lock before signalling a {@code request} upstream,
     * where the request will increase demand from zero. Increasing demand
     * from zero may initiate a database call from JDBC, so the lock must be
     * acquired first.
     * </p><p>
     * The lock is released after {@code onNext} signals have decreased demand
     * back to zero. Or, a terminal {@code onComplete/onError} signal may have
     * the lock released before demand reaches zero.
     * </p><p>
     * If demand is increased from a number greater than zero, this
     * indicates that the lock has already been acquired for a previous
     * request, and that the lock can not be released until demand
     * reaches zero. The request is sent upstream without reacquiring the
     * lock in this case.
     * </p><p>
     * If demand is a negative number, this indicates that a terminal signal
     * has already been received, either from upstream with
     * {@code onComplete/onError}, or from downstream with {@code cancel}. In
     * either case, the lock is not acquired and the request is not sent
     * upstream; If this subscription is terminated, then there will be no
     * future signals to unlock the lock.
     * </p>
     */
    @Override
    public void request(long n) {
      lock(() -> {
        long currentDemand = demand.getAndUpdate(current ->
          current < 0L
            ? current // Leave negative values as is
            : (Long.MAX_VALUE - current) < n // Check for overflow
              ? Long.MAX_VALUE
              : current + n);

        if (currentDemand >= 0)
          upstream.request(n);
        else //if (currentDemand == TERMINATED)
          unlock();
      });
    }

    /**
     * {@inheritDoc}
     * <p>
     * Decrements demand and releases the lock if it has reached zero. When
     * demand is zero, there should be no active database calls from JDBC.
     * </p><p>
     * If a {@code cancel} signal has been received from downstream, but has
     * not yet been sent upstream, then it will be sent from this method and
     * the lock will be released. The upstream publisher should detect the
     * cancel signal after it has called {@code onNext} on this subscriber, and
     * and so it should cancel any future database calls.
     * </p>
     */
    @Override
    public void onNext(T value) {

      long currentDemand = demand.getAndUpdate(current ->
        current == Long.MAX_VALUE
          ? current
          : current == CANCEL_PENDING
            ? TERMINATED
            : current - 1L);

      if (currentDemand == CANCEL_PENDING) {
        unlock();
        upstream.cancel();
      }
      else if (currentDemand > 0L) {

        if (currentDemand == 1)
          unlock();

        downstream.onNext(value);
      }
      // else:
      // Nothing is sent downstream if this subscription has been cancelled.

    }

    /**
     * {@inheritDoc}
     * <p>
     * Defers sending the {@code cancel} upstream if an {@code onNext} signal
     * is pending. If an {@code onNext} signal is pending, then there may be
     * a database call in progress, and this subscriber must wait for that call
     * to complete before releasing the lock. In this case, the demand is set
     * to a negative value, and {@link #onNext(Object)} will detect this and
     * send the {@code cancel} signal.
     * </p><p>
     * If no {@code onNext} signal is pending, then the {@code cancel} signal
     * is sent upstream immediately.
     * </p>
     */
    @Override
    public void cancel() {
      long currentDemand = demand.getAndUpdate(current ->
        current > 0 || current == CANCEL_PENDING
          ? CANCEL_PENDING
          : TERMINATED);

      if (currentDemand == 0)
        upstream.cancel();

    }

    @Override
    public void onError(Throwable error) {
      terminate();
      downstream.onError(error);
    }

    @Override
    public void onComplete() {
      terminate();
      downstream.onComplete();
    }

    /**
     * Terminates upon receiving {@code onComplete} or {@code onError}.
     * Termination has this subscriber release the lock if it is currently
     * being held. The {@link #demand} is updated so that no future request
     * signals will have this subscriber acquire the lock again.
     */
    private void terminate() {
      long currentDemand = demand.getAndSet(TERMINATED);

      if (currentDemand > 0 || currentDemand == CANCEL_PENDING)
        unlock();
    }
  }

}
