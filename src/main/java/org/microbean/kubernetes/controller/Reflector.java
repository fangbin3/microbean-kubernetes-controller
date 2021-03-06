/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2017-2018 microBean.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.microbean.kubernetes.controller;

import java.io.Closeable;
import java.io.IOException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import java.time.Duration;

import java.time.temporal.ChronoUnit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Map;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import java.util.function.Function;

import java.util.logging.Level;
import java.util.logging.Logger;

import io.fabric8.kubernetes.client.DefaultKubernetesClient; // for javadoc only
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch; // for javadoc only
import io.fabric8.kubernetes.client.Watcher;

import io.fabric8.kubernetes.client.dsl.base.BaseOperation;
import io.fabric8.kubernetes.client.dsl.base.OperationSupport;

import io.fabric8.kubernetes.client.dsl.Listable;
import io.fabric8.kubernetes.client.dsl.Versionable;
import io.fabric8.kubernetes.client.dsl.VersionWatchable;
import io.fabric8.kubernetes.client.dsl.Watchable;

import io.fabric8.kubernetes.client.dsl.internal.CustomResourceOperationsImpl;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ListMeta;

import net.jcip.annotations.GuardedBy;
import net.jcip.annotations.ThreadSafe;

import okhttp3.OkHttpClient;

import org.microbean.development.annotation.Issue;
import org.microbean.development.annotation.NonBlocking;

/**
 * A pump of sorts that continuously "pulls" logical events out of
 * Kubernetes and {@linkplain EventCache#add(Object, AbstractEvent.Type,
 * HasMetadata) adds them} to an {@link EventCache} so as to logically
 * "reflect" the contents of Kubernetes into the cache.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Instances of this class are safe for concurrent use by multiple
 * {@link Thread}s.</p>
 *
 * @param <T> a type of Kubernetes resource
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see EventCache
 */
@ThreadSafe
public class Reflector<T extends HasMetadata> implements Closeable {


  /*
   * Instance fields.
   */


  /**
   * The operation that was supplied at construction time.
   *
   * <p>This field is never {@code null}.</p>
   *
   * <p>It is guaranteed that the value of this field may be
   * assignable to a reference of type {@link Listable Listable&lt;?
   * extends KubernetesResourceList&gt;} or to a reference of type
   * {@link VersionWatchable VersionWatchable&lt;? extends Closeable,
   * Watcher&lt;T&gt;&gt;}.</p>
   *
   * @see Listable
   *
   * @see VersionWatchable
   */
  private final Object operation;

  /**
   * The resource version
   */
  private volatile Object lastSynchronizationResourceVersion;

  @GuardedBy("this")
  private ScheduledExecutorService synchronizationExecutorService;

  private final Function<? super Throwable, Boolean> synchronizationErrorHandler;

  @GuardedBy("this")
  private ScheduledFuture<?> synchronizationTask;

  private final boolean shutdownSynchronizationExecutorServiceOnClose;

  private final long synchronizationIntervalInSeconds;

  @GuardedBy("this")
  private Closeable watch;

  @GuardedBy("itself")
  private final EventCache<T> eventCache;

  /**
   * A {@link Logger} for use by this {@link Reflector}.
   *
   * <p>This field is never {@code null}.</p>
   *
   * @see #createLogger()
   */
  protected final Logger logger;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link Reflector}.
   *
   * @param <X> a type that is both an appropriate kind of {@link
   * Listable} and {@link VersionWatchable}, such as the kind of
   * operation returned by {@link
   * DefaultKubernetesClient#configMaps()} and the like
   *
   * @param operation a {@link Listable} and a {@link
   * VersionWatchable} that can report information from a Kubernetes
   * cluster; must not be {@code null}
   *
   * @param eventCache an {@link EventCache} <strong>that will be
   * synchronized on</strong> and into which {@link Event}s will be
   * logically "reflected"; must not be {@code null}
   *
   * @exception NullPointerException if {@code operation} or {@code
   * eventCache} is {@code null}
   *
   * @exception IllegalStateException if the {@link #createLogger()}
   * method returns {@code null}
   *
   * @see #Reflector(Listable, EventCache, ScheduledExecutorService,
   * Duration)
   *
   * @see #start()
   */
  @SuppressWarnings("rawtypes") // kubernetes-client's implementations of KubernetesResourceList use raw types
  public <X extends Listable<? extends KubernetesResourceList> & VersionWatchable<? extends Closeable, Watcher<T>>> Reflector(final X operation,
                                                                                                                              final EventCache<T> eventCache) {
    this(operation, eventCache, null, null, null);
  }

  /**
   * Creates a new {@link Reflector}.
   *
   * @param <X> a type that is both an appropriate kind of {@link
   * Listable} and {@link VersionWatchable}, such as the kind of
   * operation returned by {@link
   * DefaultKubernetesClient#configMaps()} and the like
   *
   * @param operation a {@link Listable} and a {@link
   * VersionWatchable} that can report information from a Kubernetes
   * cluster; must not be {@code null}
   *
   * @param eventCache an {@link EventCache} <strong>that will be
   * synchronized on</strong> and into which {@link Event}s will be
   * logically "reflected"; must not be {@code null}
   *
   * @param synchronizationInterval a {@link Duration} representing
   * the time in between one {@linkplain EventCache#synchronize()
   * synchronization operation} and another; interpreted with a
   * granularity of seconds; may be {@code null} or semantically equal
   * to {@code 0} seconds in which case no synchronization will occur
   *
   * @exception NullPointerException if {@code operation} or {@code
   * eventCache} is {@code null}
   *
   * @exception IllegalStateException if the {@link #createLogger()}
   * method returns {@code null}
   *
   * @see #Reflector(Listable, EventCache, ScheduledExecutorService,
   * Duration)
   *
   * @see #start()
   */
  @SuppressWarnings("rawtypes") // kubernetes-client's implementations of KubernetesResourceList use raw types
  public <X extends Listable<? extends KubernetesResourceList> & VersionWatchable<? extends Closeable, Watcher<T>>> Reflector(final X operation,
                                                                                                                              final EventCache<T> eventCache,
                                                                                                                              final Duration synchronizationInterval) {
    this(operation, eventCache, null, synchronizationInterval, null);
  }

  /**
   * Creates a new {@link Reflector}.
   *
   * @param <X> a type that is both an appropriate kind of {@link
   * Listable} and {@link VersionWatchable}, such as the kind of
   * operation returned by {@link
   * DefaultKubernetesClient#configMaps()} and the like
   *
   * @param operation a {@link Listable} and a {@link
   * VersionWatchable} that can report information from a Kubernetes
   * cluster; must not be {@code null}
   *
   * @param eventCache an {@link EventCache} <strong>that will be
   * synchronized on</strong> and into which {@link Event}s will be
   * logically "reflected"; must not be {@code null}
   *
   * @param synchronizationExecutorService a {@link
   * ScheduledExecutorService} to be used to tell the supplied {@link
   * EventCache} to {@linkplain EventCache#synchronize() synchronize}
   * on a schedule; may be {@code null} in which case no
   * synchronization will occur
   *
   * @param synchronizationInterval a {@link Duration} representing
   * the time in between one {@linkplain EventCache#synchronize()
   * synchronization operation} and another; may be {@code null} in
   * which case no synchronization will occur
   *
   * @exception NullPointerException if {@code operation} or {@code
   * eventCache} is {@code null}
   *
   * @exception IllegalStateException if the {@link #createLogger()}
   * method returns {@code null}
   *
   * @see #start()
   */
  @SuppressWarnings("rawtypes") // kubernetes-client's implementations of KubernetesResourceList use raw types
  public <X extends Listable<? extends KubernetesResourceList> & VersionWatchable<? extends Closeable, Watcher<T>>> Reflector(final X operation,
                                                                                                                              final EventCache<T> eventCache,
                                                                                                                              final ScheduledExecutorService synchronizationExecutorService,
                                                                                                                              final Duration synchronizationInterval) {
    this(operation, eventCache, synchronizationExecutorService, synchronizationInterval, null);
  }

  @SuppressWarnings("rawtypes") // kubernetes-client's implementations of KubernetesResourceList use raw types
  public <X extends Listable<? extends KubernetesResourceList> & VersionWatchable<? extends Closeable, Watcher<T>>> Reflector(final X operation,
                                                                                                                              final EventCache<T> eventCache,
                                                                                                                              final ScheduledExecutorService synchronizationExecutorService,
                                                                                                                              final Duration synchronizationInterval,
                                                                                                                              final Function<? super Throwable, Boolean> synchronizationErrorHandler) {
    super();
    this.logger = this.createLogger();
    if (this.logger == null) {
      throw new IllegalStateException("createLogger() == null");
    }
    final String cn = this.getClass().getName();
    final String mn = "<init>";
    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.entering(cn, mn, new Object[] { operation, eventCache, synchronizationExecutorService, synchronizationInterval });
    }
    Objects.requireNonNull(operation);
    this.eventCache = Objects.requireNonNull(eventCache);
    // TODO: research: maybe: operation.withField("metadata.resourceVersion", "0")?
    this.operation = withResourceVersion(operation, "0");

    if (synchronizationInterval == null) {
      this.synchronizationIntervalInSeconds = 0L;
    } else {
      this.synchronizationIntervalInSeconds = synchronizationInterval.get(ChronoUnit.SECONDS);
    }
    if (this.synchronizationIntervalInSeconds <= 0L) {
      this.synchronizationExecutorService = null;
      this.shutdownSynchronizationExecutorServiceOnClose = false;
      this.synchronizationErrorHandler = null;
    } else {
      this.synchronizationExecutorService = synchronizationExecutorService;
      this.shutdownSynchronizationExecutorServiceOnClose = synchronizationExecutorService == null;
      if (synchronizationErrorHandler == null) {
        this.synchronizationErrorHandler = t -> {
          if (this.logger.isLoggable(Level.SEVERE)) {
            this.logger.logp(Level.SEVERE, this.getClass().getName(), "<synchronizationTask>", t.getMessage(), t);
          }
          return true;
        };
      } else {
        this.synchronizationErrorHandler = synchronizationErrorHandler;
      }
    }
    
    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.exiting(cn, mn);
    }
  }


  /*
   * Instance methods.
   */


  /**
   * Returns a {@link Logger} that will be used for this {@link
   * Reflector}.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * <p>Overrides of this method must not return {@code null}.</p>
   *
   * @return a non-{@code null} {@link Logger}
   */
  protected Logger createLogger() {
    return Logger.getLogger(this.getClass().getName());
  }

  /**
   * Notionally closes this {@link Reflector} by terminating any
   * {@link Thread}s that it has started and invoking the {@link
   * #onClose()} method while holding this {@link Reflector}'s
   * monitor.
   *
   * @exception IOException if an error occurs
   *
   * @see #onClose()
   */
  @Override
  public synchronized final void close() throws IOException {
    final String cn = this.getClass().getName();
    final String mn = "close";
    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.entering(cn, mn);
    }
    try {
      this.closeSynchronizationExecutorService();
      if (this.watch != null) {
        this.watch.close();
      }
    } finally {
      this.onClose();
    }
    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.exiting(cn, mn);
    }
  }

  private synchronized final void cancelSynchronization() {
    final String cn = this.getClass().getName();
    final String mn = "closeSynchronizationExecutorService";
    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.entering(cn, mn);
    }
    if (this.synchronizationTask != null) {
      this.synchronizationTask.cancel(true /* interrupt the task */);
      this.synchronizationTask = null;
    }
    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.exiting(cn, mn);
    }
  }
  
  private synchronized final void closeSynchronizationExecutorService() {
    final String cn = this.getClass().getName();
    final String mn = "closeSynchronizationExecutorService";
    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.entering(cn, mn);
    }

    this.cancelSynchronization();

    if (this.synchronizationExecutorService != null && this.shutdownSynchronizationExecutorServiceOnClose) {

      // Stop accepting new tasks.  Not that any will be showing up
      // anyway, but it's the right thing to do.
      this.synchronizationExecutorService.shutdown();

      try {
        if (!this.synchronizationExecutorService.awaitTermination(60L, TimeUnit.SECONDS)) {
          this.synchronizationExecutorService.shutdownNow();
          if (!this.synchronizationExecutorService.awaitTermination(60L, TimeUnit.SECONDS)) {
            if (this.logger.isLoggable(Level.WARNING)) {
              this.logger.logp(Level.WARNING, cn, mn, "synchronizationExecutorService did not terminate cleanly after 60 seconds");
            }
          }
        }
      } catch (final InterruptedException interruptedException) {
        this.synchronizationExecutorService.shutdownNow();
        Thread.currentThread().interrupt();
      }
      
    }

    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.exiting(cn, mn);
    }
  }

  /**
   * As the name implies, sets up <em>synchronization</em>, which is
   * the act of the downstream event cache telling its associated
   * event listeners that there are items remaining to be processed,
   * and returns a {@link Future} reprsenting the scheduled, repeating
   * task.
   *
   * <p>This method schedules repeated invocations of the {@link
   * #synchronize()} method.</p>
   *
   * <p>This method may return {@code null}.</p>
   *
   * @return a {@link Future} representing the scheduled repeating
   * synchronization task, or {@code null} if no such task was
   * scheduled
   *
   * @see #synchronize()
   *
   * @see EventCache#synchronize()
   */
  private synchronized final Future<?> setUpSynchronization() {
    final String cn = this.getClass().getName();
    final String mn = "setUpSynchronization";
    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.entering(cn, mn);
    }

    if (this.synchronizationIntervalInSeconds > 0L) {
      if (this.synchronizationExecutorService == null || this.synchronizationExecutorService.isTerminated()) {
        this.synchronizationExecutorService = Executors.newScheduledThreadPool(1);
        if (this.synchronizationExecutorService instanceof ScheduledThreadPoolExecutor) {
          ((ScheduledThreadPoolExecutor)this.synchronizationExecutorService).setRemoveOnCancelPolicy(true);
        }
      }
      if (this.synchronizationTask == null) {
        if (this.logger.isLoggable(Level.INFO)) {
          this.logger.logp(Level.INFO, cn, mn, "Scheduling downstream synchronization every {0} seconds", Long.valueOf(this.synchronizationIntervalInSeconds));
        }
        this.synchronizationTask = this.synchronizationExecutorService.scheduleWithFixedDelay(this::synchronize, 0L, this.synchronizationIntervalInSeconds, TimeUnit.SECONDS);
      }
      assert this.synchronizationExecutorService != null;
      assert this.synchronizationTask != null;
    }

    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.exiting(cn, mn, this.synchronizationTask);
    }
    return this.synchronizationTask;
  }

  private final void synchronize() {
    final String cn = this.getClass().getName();
    final String mn = "synchronize";
    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.entering(cn, mn);
    }

    if (this.shouldSynchronize()) {
      if (this.logger.isLoggable(Level.FINE)) {
        this.logger.logp(Level.FINE, cn, mn, "Synchronizing event cache with its downstream consumers");
      }
      Throwable throwable = null;
      synchronized (this.eventCache) {
        try {
          this.eventCache.synchronize();
        } catch (final Throwable e) {
          assert e instanceof RuntimeException || e instanceof Error;
          throwable = e;
        }
      }
      if (throwable != null && !this.synchronizationErrorHandler.apply(throwable)) {
        if (throwable instanceof RuntimeException) {
          throw (RuntimeException)throwable;
        } else if (throwable instanceof Error) {
          throw (Error)throwable;
        } else {
          assert !(throwable instanceof Exception) : "Signature changed for EventCache#synchronize()";
        }
      }
    }

    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.exiting(cn, mn);
    }
  }

  /**
   * Returns whether, at any given moment, this {@link Reflector}
   * should cause its {@link EventCache} to {@linkplain
   * EventCache#synchronize() synchronize}.
   *
   * <p>The default implementation of this method returns {@code true}
   * if this {@link Reflector} was constructed with an explicit
   * synchronization interval or {@link ScheduledExecutorService} or
   * both.</p>
   *
   * <h2>Design Notes</h2>
   *
   * <p>This code follows the Go code in the Kubernetes {@code
   * client-go/tools/cache} package.  One thing that becomes clear
   * when looking at all of this through an object-oriented lens is
   * that it is the {@link EventCache} (the {@code delta_fifo}, in the
   * Go code) that is ultimately in charge of synchronizing.  It is
   * not clear why in the Go code this is a function of a reflector.
   * In an object-oriented world, perhaps the {@link EventCache}
   * itself should be in charge of resynchronization schedules, but we
   * choose to follow the Go code's division of responsibilities
   * here.</p>
   *
   * @return {@code true} if this {@link Reflector} should cause its
   * {@link EventCache} to {@linkplain EventCache#synchronize()
   * synchronize}; {@code false} otherwise
   */
  protected boolean shouldSynchronize() {
    final String cn = this.getClass().getName();
    final String mn = "shouldSynchronize";
    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.entering(cn, mn);
    }
    final boolean returnValue;
    synchronized (this) {
      returnValue = this.synchronizationExecutorService != null;
    }
    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.exiting(cn, mn, Boolean.valueOf(returnValue));
    }
    return returnValue;
  }

  // Not used; not used in the Go code either?!
  private final Object getLastSynchronizationResourceVersion() {
    return this.lastSynchronizationResourceVersion;
  }

  private final void setLastSynchronizationResourceVersion(final Object resourceVersion) {
    this.lastSynchronizationResourceVersion = resourceVersion;
  }

  /**
   * Using the {@code operation} supplied at construction time,
   * {@linkplain Listable#list() lists} appropriate Kubernetes
   * resources, and then, on a separate {@link Thread}, {@linkplain
   * VersionWatchable sets up a watch} on them, calling {@link
   * EventCache#replace(Collection, Object)} and {@link
   * EventCache#add(Object, AbstractEvent.Type, HasMetadata)} methods
   * as appropriate.
   *
   * <p>For convenience only, this method returns a {@link Future}
   * representing any scheduled synchronization task created as a
   * result of the user's having supplied a {@link Duration} at
   * construction time.  The return value may be (and often is) safely
   * ignored.  Invoking {@link Future#cancel(boolean)} on the returned
   * {@link Future} will result in the scheduled synchronization task
   * being cancelled irrevocably.</p>
   *
   * <p>This method may return {@code null}.</p>
   *
   * <p>The calling {@link Thread} is not blocked by invocations of
   * this method.</p> 
   *
   * @return a {@link Future} representing a scheduled synchronization
   * operation, or {@code null}
   *
   * @exception IOException if a watch has previously been established
   * and could not be {@linkplain Watch#close() closed}
   *
   * @see #close()
   */
  @NonBlocking
  public final Future<?> start() throws IOException {
    final String cn = this.getClass().getName();
    final String mn = "start";
    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.entering(cn, mn);
    }

    Future<?> returnValue = null;
    synchronized (this) {

      try {
      
        // If somehow we got called while a watch already exists, then
        // close the old watch (we'll replace it).  Note that,
        // critically, the onClose() method of our watch handler, sets
        // this reference to null, so if the watch is in the process
        // of being closed, this little block won't be executed.
        if (this.watch != null) {
          final Closeable watch = this.watch;
          this.watch = null;
          watch.close();
        }
        
        // Run a list operation, and get the resourceVersion of that list.
        @SuppressWarnings("unchecked")
        final KubernetesResourceList<? extends T> list = ((Listable<? extends KubernetesResourceList<? extends T>>)this.operation).list();
        assert list != null;

        final ListMeta metadata = list.getMetadata();
        assert metadata != null;

        final String resourceVersion = metadata.getResourceVersion();
        assert resourceVersion != null;
        
        // Using the results of that list operation, do a full replace
        // on the EventCache with them.
        final Collection<? extends T> replacementItems;
        final Collection<? extends T> items = list.getItems();
        if (items == null || items.isEmpty()) {
          replacementItems = Collections.emptySet();
        } else {
          replacementItems = Collections.unmodifiableCollection(new ArrayList<>(items));
        }
        synchronized (eventCache) {
          this.eventCache.replace(replacementItems, resourceVersion);
        }
        
        // Record the resource version we captured during our list
        // operation.
        this.setLastSynchronizationResourceVersion(resourceVersion);
        
        // Now that we've vetted that our list operation works (i.e. no
        // syntax errors, no connectivity problems) we can schedule
        // synchronizations if necessary.
        //
        // A synchronization is an operation where, if allowed, our
        // eventCache goes through its set of known objects and--for
        // any that are not enqueued for further processing
        // already--fires a synchronization event of type
        // MODIFICATION.  This happens on a schedule, not in reaction
        // to an event.  This allows its downstream processors a
        // chance to try to bring system state in line with desired
        // state, even if no events have occurred (kind of like a
        // heartbeat).  See
        // https://engineering.bitnami.com/articles/a-deep-dive-into-kubernetes-controllers.html#resyncperiod.
        this.setUpSynchronization();
        returnValue = this.synchronizationTask;
        
        // Now that we've taken care of our list() operation, set up our
        // watch() operation.
        @SuppressWarnings("unchecked")
        final Versionable<? extends Watchable<? extends Closeable, Watcher<T>>> versionableOperation = (Versionable<? extends Watchable<? extends Closeable, Watcher<T>>>)this.operation;
        this.watch = withResourceVersion(versionableOperation, resourceVersion).watch(new WatchHandler());

      } catch (final IOException | RuntimeException exception) {
        this.cancelSynchronization();
        if (this.watch != null) {
          try {
            // TODO: haven't seen it, but reason hard about deadlock
            // here; see
            // WatchHandler#onClose(KubernetesClientException) which
            // *can* call start() (this method) with the monitor.  I
            // *think* we're in the clear here:
            // onClose(KubernetesClientException) will only (re-)call
            // start() if the supplied KubernetesClientException is
            // non-null.  In this case, it should be, because this is
            // an ordinary close() call.
            this.watch.close();
          } catch (final IOException | RuntimeException suppressMe) {
            exception.addSuppressed(suppressMe);
          }
          this.watch = null;
        }
        throw exception;
      }
    }

    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.exiting(cn, mn, returnValue);
    }
    return returnValue;
  }

  /**
   * Invoked when {@link #close()} is invoked.
   *
   * <p>The default implementation of this method does nothing.</p>
   */
  protected synchronized void onClose() {

  }


  /*
   * Hacks.
   */


  @Issue(
    id = "kubernetes-client/1099",
    uri = "https://github.com/fabric8io/kubernetes-client/issues/1099"
  )
  @SuppressWarnings({"rawtypes", "unchecked"})
  private final Watchable<? extends Closeable, Watcher<T>> withResourceVersion(final Versionable<? extends Watchable<? extends Closeable, Watcher<T>>> operation, final String resourceVersion) {
    Objects.requireNonNull(operation);
    Objects.requireNonNull(resourceVersion);
    Watchable<? extends Closeable, Watcher<T>> returnValue = null;
    if (operation instanceof CustomResourceOperationsImpl) {
      final CustomResourceOperationsImpl old = (CustomResourceOperationsImpl)operation;
      try {
        returnValue =
          new CustomResourceOperationsImpl(getClient(old),
                                           old.getConfig(),
                                           getApiGroup(old),
                                           old.getAPIVersion(),
                                           getResourceT(old),
                                           old.getNamespace(),
                                           old.getName(),
                                           old.isCascading(),
                                           (T)old.getItem(),
                                           resourceVersion,
                                           old.isReloadingFromServer(),
                                           old.getType(),
                                           old.getListType(),
                                           old.getDoneableType());
      } catch (final ReflectiveOperationException reflectiveOperationException) {
        throw new KubernetesClientException(reflectiveOperationException.getMessage(), reflectiveOperationException);
      }
    } else {
      returnValue = operation.withResourceVersion(resourceVersion);
    }
    assert returnValue != null;
    return returnValue;
  }

  private static final OkHttpClient getClient(final OperationSupport operation) throws ReflectiveOperationException {
    Objects.requireNonNull(operation);
    final Field clientField = OperationSupport.class.getDeclaredField("client");
    assert !clientField.isAccessible();
    assert OkHttpClient.class.equals(clientField.getType());
    OkHttpClient returnValue = null;
    try {
      clientField.setAccessible(true);
      returnValue = (OkHttpClient)clientField.get(operation);
    } finally {
      clientField.setAccessible(false);
    }
    return returnValue;
  }

  private static final String getApiGroup(final OperationSupport operation) throws ReflectiveOperationException {
    Objects.requireNonNull(operation);
    final Field apiGroupField = OperationSupport.class.getDeclaredField("apiGroup");
    assert !apiGroupField.isAccessible();
    assert String.class.equals(apiGroupField.getType());
    String returnValue = null;
    try {
      apiGroupField.setAccessible(true);
      returnValue = (String)apiGroupField.get(operation);
    } finally {
      apiGroupField.setAccessible(false);
    }
    return returnValue;
  }

  private static final String getResourceT(final OperationSupport operation) throws ReflectiveOperationException {
    Objects.requireNonNull(operation);
    final Field resourceTField = OperationSupport.class.getDeclaredField("resourceT");
    assert !resourceTField.isAccessible();
    assert String.class.equals(resourceTField.getType());
    String returnValue = null;
    try {
      resourceTField.setAccessible(true);
      returnValue = (String)resourceTField.get(operation);
    } finally {
      resourceTField.setAccessible(false);
    }
    return returnValue;
  }

  private static final Map<String, String> getFields(final BaseOperation<?, ?, ?, ?> operation) throws ReflectiveOperationException {
    Objects.requireNonNull(operation);
    final Method getFieldsMethod = BaseOperation.class.getDeclaredMethod("getFields");
    assert !getFieldsMethod.isAccessible();
    assert Map.class.equals(getFieldsMethod.getReturnType());
    Map<String, String> returnValue = null;
    try {
      getFieldsMethod.setAccessible(true);
      @SuppressWarnings("unchecked")
      final Map<String, String> temp = (Map<String, String>)getFieldsMethod.invoke(operation);
      returnValue = temp;
    } finally {
      getFieldsMethod.setAccessible(false);
    }
    return returnValue;
  }

  private static final Map<String, String> getLabels(final BaseOperation<?, ?, ?, ?> operation) throws ReflectiveOperationException {
    Objects.requireNonNull(operation);
    final Method getLabelsMethod = BaseOperation.class.getDeclaredMethod("getLabels");
    assert !getLabelsMethod.isAccessible();
    assert Map.class.equals(getLabelsMethod.getReturnType());
    Map<String, String> returnValue = null;
    try {
      getLabelsMethod.setAccessible(true);
      @SuppressWarnings("unchecked")
      final Map<String, String> temp = (Map<String, String>)getLabelsMethod.invoke(operation);
      returnValue = temp;
    } finally {
      getLabelsMethod.setAccessible(false);
    }
    return returnValue;
  }

  private static final Map<String, String[]> getLabelsIn(final BaseOperation<?, ?, ?, ?> operation) throws ReflectiveOperationException {
    Objects.requireNonNull(operation);
    final Method getLabelsInMethod = BaseOperation.class.getDeclaredMethod("getLabelsIn");
    assert !getLabelsInMethod.isAccessible();
    assert Map.class.equals(getLabelsInMethod.getReturnType());
    Map<String, String[]> returnValue = null;
    try {
      getLabelsInMethod.setAccessible(true);
      @SuppressWarnings("unchecked")
      final Map<String, String[]> temp = (Map<String, String[]>)getLabelsInMethod.invoke(operation);
      returnValue = temp;
    } finally {
      getLabelsInMethod.setAccessible(false);
    }
    return returnValue;
  }

  private static final Map<String, String> getLabelsNot(final BaseOperation<?, ?, ?, ?> operation) throws ReflectiveOperationException {
    Objects.requireNonNull(operation);
    final Method getLabelsNotMethod = BaseOperation.class.getDeclaredMethod("getLabelsNot");
    assert !getLabelsNotMethod.isAccessible();
    assert Map.class.equals(getLabelsNotMethod.getReturnType());
    Map<String, String> returnValue = null;
    try {
      getLabelsNotMethod.setAccessible(true);
      @SuppressWarnings("unchecked")
      final Map<String, String> temp = (Map<String, String>)getLabelsNotMethod.invoke(operation);
      returnValue = temp;
    } finally {
      getLabelsNotMethod.setAccessible(false);
    }
    return returnValue;
  }

  private static final Map<String, String[]> getLabelsNotIn(final BaseOperation<?, ?, ?, ?> operation) throws ReflectiveOperationException {
    Objects.requireNonNull(operation);
    final Method getLabelsNotInMethod = BaseOperation.class.getDeclaredMethod("getLabelsNotIn");
    assert !getLabelsNotInMethod.isAccessible();
    assert Map.class.equals(getLabelsNotInMethod.getReturnType());
    Map<String, String[]> returnValue = null;
    try {
      getLabelsNotInMethod.setAccessible(true);
      @SuppressWarnings("unchecked")
      final Map<String, String[]> temp = (Map<String, String[]>)getLabelsNotInMethod.invoke(operation);
      returnValue = temp;
    } finally {
      getLabelsNotInMethod.setAccessible(false);
    }
    return returnValue;
  }


  /*
   * Inner and nested classes.
   */


  /**
   * A {@link Watcher} of Kubernetes resources.
   *
   * @author <a href="https://about.me/lairdnelson"
   * target="_parent">Laird Nelson</a>
   *
   * @see Watcher
   */
  private final class WatchHandler implements Watcher<T> {


    /*
     * Constructors.
     */


    /**
     * Creates a new {@link WatchHandler}.
     */
    private WatchHandler() {
      super();
      final String cn = this.getClass().getName();
      final String mn = "<init>";
      if (logger.isLoggable(Level.FINER)) {
        logger.entering(cn, mn);
        logger.exiting(cn, mn);
      }
    }


    /*
     * Instance methods.
     */


    /**
     * Calls the {@link EventCache#add(Object, AbstractEvent.Type,
     * HasMetadata)} method on the enclosing {@link Reflector}'s
     * associated {@link EventCache} with information harvested from
     * the supplied {@code resource}, and using an {@link Event.Type}
     * selected appropriately given the supplied {@link
     * Watcher.Action}.
     *
     * @param action the kind of Kubernetes event that happened; must
     * not be {@code null}
     *
     * @param resource the {@link HasMetadata} object that was
     * affected; must not be {@code null}
     *
     * @exception NullPointerException if {@code action} or {@code
     * resource} was {@code null}
     *
     * @exception IllegalStateException if another error occurred
     */
    @Override
    public final void eventReceived(final Watcher.Action action, final T resource) {
      final String cn = this.getClass().getName();
      final String mn = "eventReceived";
      if (logger.isLoggable(Level.FINER)) {
        logger.entering(cn, mn, new Object[] { action, resource });
      }
      Objects.requireNonNull(action);
      Objects.requireNonNull(resource);

      final ObjectMeta metadata = resource.getMetadata();
      assert metadata != null;

      final Event.Type eventType;

      switch (action) {
      case ADDED:
        eventType = Event.Type.ADDITION;
        break;
      case MODIFIED:
        eventType = Event.Type.MODIFICATION;
        break;
      case DELETED:
        eventType = Event.Type.DELETION;
        break;
      case ERROR:
        // Uh...the Go code has:
        //
        //   if event.Type == watch.Error {
        //     return apierrs.FromObject(event.Object)
        //   }
        //
        // Now, apierrs.FromObject is here:
        // https://github.com/kubernetes/apimachinery/blob/kubernetes-1.9.2/pkg/api/errors/errors.go#L80-L88
        // This is looking for a Status object.  But
        // WatchConnectionHandler will never forward on such a thing:
        // https://github.com/fabric8io/kubernetes-client/blob/v3.1.8/kubernetes-client/src/main/java/io/fabric8/kubernetes/client/dsl/internal/WatchConnectionManager.java#L246-L258
        //
        // So it follows that if by some chance we get here, resource
        // will definitely be a HasMetadata.  We go back to the Go
        // code again, and remember that if the type is Error, the
        // equivalent of this watch handler simply returns and goes home.
        //
        // Now, if we were to throw a RuntimeException here, which is
        // the idiomatic equivalent of returning and going home, this
        // would cause a watch reconnect:
        // https://github.com/fabric8io/kubernetes-client/blob/v3.1.8/kubernetes-client/src/main/java/io/fabric8/kubernetes/client/dsl/internal/WatchConnectionManager.java#L159-L205
        // ...up to the reconnect limit.
        //
        // ...which is fine, but I'm not sure that in an error case a
        // WatchEvent will ever HAVE a HasMetadata as its payload.
        // Which means MAYBE we'll never get here.  But if we do, all
        // we can do is throw a RuntimeException...which ends up
        // reducing to the same case as the default case below, so we
        // fall through.
      default:
        eventType = null;
        throw new IllegalStateException();
      }

      // Add an Event of the proper kind to our EventCache.
      if (eventType != null) {
        if (logger.isLoggable(Level.FINE)) {
          logger.logp(Level.FINE, cn, mn, "Adding event to cache: {0} {1}", new Object[] { eventType, resource });
        }
        synchronized (eventCache) {
          eventCache.add(Reflector.this, eventType, resource);
        }
      }

      // Record the most recent resource version we're tracking to be
      // that of this last successful watch() operation.  We set it
      // earlier during a list() operation.
      setLastSynchronizationResourceVersion(metadata.getResourceVersion());

      if (logger.isLoggable(Level.FINER)) {
        logger.exiting(cn, mn);
      }
    }

    /**
     * Invoked when the Kubernetes client connection closes.
     *
     * @param exception any {@link KubernetesClientException} that
     * caused this closing to happen; may be {@code null}
     */
    @Override
    public final void onClose(final KubernetesClientException exception) {
      final String cn = this.getClass().getName();
      final String mn = "onClose";
      if (logger.isLoggable(Level.FINER)) {
        logger.entering(cn, mn, exception);
      }

      synchronized (Reflector.this) {
        // No need to close it; we're being called because it's
        // closing.
        Reflector.this.watch = null;
      }
      
      if (exception != null) {
        if (logger.isLoggable(Level.WARNING)) {
          logger.logp(Level.WARNING, cn, mn, exception.getMessage(), exception);
        }
        // See
        // https://github.com/kubernetes/client-go/blob/5f85fe426e7aa3c1df401a7ae6c1ba837bd76be9/tools/cache/reflector.go#L204.
        try {
          Reflector.this.start();
        } catch (final IOException ioException) {
          if (logger.isLoggable(Level.SEVERE)) {
            logger.logp(Level.SEVERE, cn, mn, ioException.getMessage(), ioException);
          }
        }
      }
      if (logger.isLoggable(Level.FINER)) {
        logger.exiting(cn, mn, exception);
      }
    }

  }

}
