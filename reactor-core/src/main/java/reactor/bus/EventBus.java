/*
 * Copyright (c) 2011-2014 Pivotal Software, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package reactor.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.Environment;
import reactor.bus.filter.PassThroughFilter;
import reactor.bus.registry.CachingRegistry;
import reactor.bus.registry.Registration;
import reactor.bus.registry.Registry;
import reactor.bus.routing.ArgumentConvertingConsumerInvoker;
import reactor.bus.routing.ConsumerFilteringRouter;
import reactor.bus.routing.Router;
import reactor.bus.selector.ClassSelector;
import reactor.bus.selector.Selector;
import reactor.bus.selector.Selectors;
import reactor.bus.spec.EventBusSpec;
import reactor.core.Dispatcher;
import reactor.core.dispatch.SynchronousDispatcher;
import reactor.core.support.Assert;
import reactor.core.support.UUIDUtils;
import reactor.fn.Consumer;
import reactor.fn.Function;
import reactor.fn.Supplier;
import reactor.fn.support.SingleUseConsumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * A reactor is an event gateway that allows other components to register {@link Event} {@link Consumer}s that can
 * subsequently be notified of events. A consumer is typically registered with a {@link Selector} which, by matching on
 * the notification key, governs which events the consumer will receive. </p> When a {@literal Reactor} is notified of
 * an {@link Event}, a task is dispatched using the reactor's {@link Dispatcher} which causes it to be executed on a
 * thread based on the implementation of the {@link Dispatcher} being used.
 *
 * @author Jon Brisbin
 * @author Stephane Maldini
 * @author Andy Wilkinson
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class EventBus implements Observable, Consumer<Event<?>> {

	private static final Router DEFAULT_EVENT_ROUTER = new ConsumerFilteringRouter(
			new PassThroughFilter(), new ArgumentConvertingConsumerInvoker(null)
	);

	private final Dispatcher            dispatcher;
	private final Registry<Consumer<?>> consumerRegistry;
	private final Router                router;
	private final Consumer<Throwable>   dispatchErrorHandler;
	private final Consumer<Throwable> uncaughtErrorHandler;

	private volatile UUID id;


	/**
	 * Create a new {@link reactor.bus.spec.EventBusSpec} to configure a Reactor.
	 *
	 * @return The Reactor spec
	 */
	public static EventBusSpec config() {
		return new EventBusSpec();
	}

	/**
	 * Create a new {@link EventBus} using the given {@link reactor.Environment}.
	 *
	 * @param env
	 * 		The {@link reactor.Environment} to use.
	 *
	 * @return A new {@link EventBus}
	 */
	public static EventBus create(Environment env) {
		return new EventBusSpec().env(env).dispatcher(env.getDefaultDispatcher()).get();
	}

	/**
	 * Create a new {@link EventBus} using the given {@link reactor.Environment} and dispatcher name.
	 *
	 * @param env
	 * 		The {@link reactor.Environment} to use.
	 * @param dispatcher
	 * 		The name of the {@link reactor.core.Dispatcher} to use.
	 *
	 * @return A new {@link EventBus}
	 */
	public static EventBus create(Environment env, String dispatcher) {
		return new EventBusSpec().env(env).dispatcher(dispatcher).get();
	}

	/**
	 * Create a new {@link EventBus} using the given {@link reactor.Environment} and {@link
	 * reactor.core.Dispatcher}.
	 *
	 * @param env
	 * 		The {@link reactor.Environment} to use.
	 * @param dispatcher
	 * 		The {@link reactor.core.Dispatcher} to use.
	 *
	 * @return A new {@link EventBus}
	 */
	public static EventBus create(Environment env, Dispatcher dispatcher) {
		return new EventBusSpec().env(env).dispatcher(dispatcher).get();
	}

	/**
	 * Create a new {@literal Reactor} that uses the given {@link Dispatcher}. The reactor will use a default {@link
	 * reactor.bus.routing.Router} that broadcast events to all of the registered consumers that {@link
	 * Selector#matches(Object) match}
	 * the notification key and does not perform any type conversion.
	 *
	 * @param dispatcher
	 * 		The {@link Dispatcher} to use. May be {@code null} in which case a new {@link SynchronousDispatcher} is used
	 */
	public EventBus(@Nullable Dispatcher dispatcher) {
		this(dispatcher, null);
	}

	/**
	 * Create a new {@literal Reactor} that uses the given {@link Dispatcher}. The reactor will use a default {@link
	 * CachingRegistry}.
	 *
	 * @param dispatcher
	 * 		The {@link Dispatcher} to use. May be {@code null} in which case a new synchronous  dispatcher is used.
	 * @param router
	 * 		The {@link Router} used to route events to {@link Consumer Consumers}. May be {@code null} in which case the
	 * 		default event router that broadcasts events to all of the registered consumers that {@link
	 * 		Selector#matches(Object) match} the notification key and does not perform any type conversion will be used.
	 */
	public EventBus(@Nullable Dispatcher dispatcher,
	                @Nullable Router router) {
		this(dispatcher, router, null, null);
	}

	public EventBus(@Nullable Dispatcher dispatcher,
	                @Nullable Router router,
	                @Nullable Consumer<Throwable> dispatchErrorHandler,
	                @Nullable final Consumer<Throwable> uncaughtErrorHandler) {
		this(new CachingRegistry<Consumer<?>>(),
				dispatcher,
				router,
				dispatchErrorHandler,
				uncaughtErrorHandler);
	}

	/**
	 * Create a new {@literal Reactor} that uses the given {@code dispatacher} and {@code eventRouter}.
	 *
	 * @param dispatcher
	 * 		The {@link Dispatcher} to use. May be {@code null} in which case a new synchronous  dispatcher is used.
	 * @param router
	 * 		The {@link Router} used to route events to {@link Consumer Consumers}. May be {@code null} in which case the
	 * 		default event router that broadcasts events to all of the registered consumers that {@link
	 * 		Selector#matches(Object) match} the notification key and does not perform any type conversion will be used.
	 * @param consumerRegistry
	 * 		The {@link Registry} to be used to match {@link Selector} and dispatch to {@link Consumer}.
	 */
	public EventBus(@Nonnull Registry<Consumer<?>> consumerRegistry,
	                @Nullable Dispatcher dispatcher,
	                @Nullable Router router,
	                @Nullable Consumer<Throwable> dispatchErrorHandler,
	                @Nullable final Consumer<Throwable> uncaughtErrorHandler) {
		Assert.notNull(consumerRegistry, "Consumer Registry cannot be null.");
		this.consumerRegistry = consumerRegistry;
		this.dispatcher = (null == dispatcher ? SynchronousDispatcher.INSTANCE : dispatcher);
		this.router = (null == router ? DEFAULT_EVENT_ROUTER : router);
		if (null == dispatchErrorHandler) {
			this.dispatchErrorHandler = new Consumer<Throwable>() {
				@Override
				public void accept(Throwable t) {
					Class<? extends Throwable> type = t.getClass();
					EventBus.this.router.route(type,
							Event.wrap(t),
							EventBus.this.consumerRegistry.select(type),
							null,
							null);
				}
			};
		} else {
			this.dispatchErrorHandler = dispatchErrorHandler;
		}

		this.uncaughtErrorHandler = uncaughtErrorHandler;

		this.on(new ClassSelector(Throwable.class), new Consumer<Event<Throwable>>() {
			Logger log;

			@Override
			public void accept(Event<Throwable> ev) {
				if (null == uncaughtErrorHandler) {
					if (null == log) {
						log = LoggerFactory.getLogger(EventBus.class);
					}
					log.error(ev.getData().getMessage(), ev.getData());
				} else {
					uncaughtErrorHandler.accept(ev.getData());
				}
			}
		});
	}

	/**
	 * Get the unique, time-used {@link UUID} of this {@literal Reactor}.
	 *
	 * @return The {@link UUID} of this {@literal Reactor}.
	 */
	public synchronized UUID getId() {
		if (null == id) {
			id = UUIDUtils.create();
		}
		return id;
	}

	/**
	 * Get the {@link Registry} is use to maintain the {@link Consumer}s currently listening for events on this {@literal
	 * Reactor}.
	 *
	 * @return The {@link Registry} in use.
	 */
	public Registry<Consumer<?>> getConsumerRegistry() {
		return consumerRegistry;
	}

	/**
	 * Get the {@link Dispatcher} currently in use.
	 *
	 * @return The {@link Dispatcher}.
	 */
	public Dispatcher getDispatcher() {
		return dispatcher;
	}

	/**
	 * Get the {@link reactor.bus.routing.Router} used to route events to {@link Consumer Consumers}.
	 *
	 * @return The {@link reactor.bus.routing.Router}.
	 */
	public Router getRouter() {
		return router;
	}

	public Consumer<Throwable> getDispatchErrorHandler() {
		return dispatchErrorHandler;
	}

	public Consumer<Throwable> getUncaughtErrorHandler() {
		return uncaughtErrorHandler;
	}

	@Override
	public boolean respondsToKey(Object key) {
		for (Registration<?> reg : consumerRegistry.select(key)) {
			if (!reg.isCancelled()) {
				return true;
			}
		}
		return false;
	}

	@Override
	public <E extends Event<?>> Registration<Consumer<E>> on(final Selector selector, final Consumer<E> consumer) {
		Assert.notNull(selector, "Selector cannot be null.");
		Assert.notNull(consumer, "Consumer cannot be null.");
		if (null != selector.getHeaderResolver()) {
			Consumer<E> proxyConsumer = new Consumer<E>() {
				@Override
				public void accept(E e) {
					e.getHeaders().setAll(selector.getHeaderResolver().resolve(e.getKey()));
					consumer.accept(e);
				}
			};
			return consumerRegistry.register(selector, proxyConsumer);
		}else{
			return consumerRegistry.register(selector, consumer);
		}
	}

	@Override
	public <E extends Event<?>, V> Registration<Consumer<E>> receive(Selector sel, Function<E, V> fn) {
		return on(sel, new ReplyToConsumer<E, V>(fn));
	}

	@Override
	public <E extends Event<?>> EventBus notify(Object key, E ev) {
		Assert.notNull(key, "Key cannot be null.");
		Assert.notNull(ev, "Event cannot be null.");
		ev.setKey(key);
		dispatcher.dispatch(ev, this, dispatchErrorHandler);

		return this;
	}

	@Override
	public <S extends Supplier<? extends Event<?>>> EventBus notify(Object key, S supplier) {
		return notify(key, supplier.get());
	}

	@Override
	public EventBus notify(Object key) {
		return notify(key, new Event<Void>(Void.class));
	}

	@Override
	public <E extends Event<?>> EventBus send(Object key, E ev) {
		return notify(key, new ReplyToEvent(ev, this));
	}

	@Override
	public <S extends Supplier<? extends Event<?>>> EventBus send(Object key, S supplier) {
		return notify(key, new ReplyToEvent(supplier.get(), this));
	}

	@Override
	public <E extends Event<?>> EventBus send(Object key, E ev, Observable replyTo) {
		return notify(key, new ReplyToEvent(ev, replyTo));
	}

	@Override
	public <S extends Supplier<? extends Event<?>>> EventBus send(Object key, S supplier, Observable replyTo) {
		return notify(key, new ReplyToEvent(supplier.get(), replyTo));
	}

	@Override
	public <REQ extends Event<?>, RESP extends Event<?>> EventBus sendAndReceive(Object key,
	                                                                            REQ ev,
	                                                                            Consumer<RESP> reply) {
		Selector sel = Selectors.anonymous();
		on(sel, new SingleUseConsumer<RESP>(reply)).cancelAfterUse();
		notify(key, ev.setReplyTo(sel.getObject()));
		return this;
	}

	@Override
	public <REQ extends Event<?>, RESP extends Event<?>, S extends Supplier<REQ>> EventBus sendAndReceive(Object key,
	                                                                                                     S supplier,
	                                                                                                     Consumer<RESP> reply) {
		return sendAndReceive(key, supplier.get(), reply);
	}

	@Override
	public <T> Consumer<Event<T>> prepare(final Object key) {
		return new Consumer<Event<T>>() {
			final List<Registration<? extends Consumer<?>>> regs = consumerRegistry.select(key);
			final int size = regs.size();

			@Override
			public void accept(Event<T> ev) {
				for (int i = 0; i < size; i++) {
					Registration<Consumer<Event<?>>> reg = (Registration<Consumer<Event<?>>>) regs.get(i);
					dispatcher.dispatch(ev.setKey(key), reg.getObject(), dispatchErrorHandler);
				}
			}
		};
	}

	@Override
	public <T> Consumer<Iterable<Event<T>>> batchNotify(final Object key) {
		return batchNotify(key, null);
	}

	@Override
	public <T> Consumer<Iterable<Event<T>>> batchNotify(final Object key, final Consumer<Void> completeConsumer) {
		return new Consumer<Iterable<Event<T>>>() {
			final Consumer<Iterable<Event<T>>> batchConsumer = new Consumer<Iterable<Event<T>>>() {
				@Override
				public void accept(Iterable<Event<T>> event) {
					List<Registration<? extends Consumer<?>>> regs = consumerRegistry.select(key);
					for (Event<T> batchedEvent : event) {
						for (Registration<? extends Consumer<?>> registration : regs) {
							if(registration.getClass().isAssignableFrom(batchedEvent.getClass())){
								router.route(null, batchedEvent, null, (Consumer<Event<T>>)registration.getObject(),
										dispatchErrorHandler);
							}
						}
					}
					if (completeConsumer != null) {
						completeConsumer.accept(null);
					}
				}
			};

			@Override
			public void accept(Iterable<Event<T>> evs) {
				dispatcher.dispatch(evs, batchConsumer, dispatchErrorHandler);
			}
		};
	}

	/**
	 * Schedule an arbitrary {@link reactor.fn.Consumer} to be executed on the current Reactor  {@link
	 * reactor.core.Dispatcher}, passing the given {@param data}.
	 *
	 * @param consumer
	 * 		The {@link reactor.fn.Consumer} to invoke.
	 * @param data
	 * 		The data to pass to the consumer.
	 * @param <T>
	 * 		The type of the data.
	 */
	public <T> void schedule(final Consumer<T> consumer, final T data) {
		dispatcher.dispatch(null, new Consumer<Event<?>>() {
			@Override
			public void accept(Event<?> event) {
				consumer.accept(data);
			}
		}, dispatchErrorHandler);
	}

	@Override
	public void accept(Event<?> event) {
		router.route(event.getKey(), event, consumerRegistry.select(event.getKey()), null, dispatchErrorHandler);
	}

	public static class ReplyToEvent<T> extends Event<T> {
		private static final long serialVersionUID = 1937884784799135647L;
		private final Observable replyToObservable;

		private ReplyToEvent(Headers headers, T data, Object replyTo,
		                     Observable replyToObservable,
		                     Consumer<Throwable> errorConsumer) {
			super(headers, data, errorConsumer);
			setReplyTo(replyTo);
			this.replyToObservable = replyToObservable;
		}

		private ReplyToEvent(Event<T> delegate, Observable replyToObservable) {
			this(delegate.getHeaders(), delegate.getData(), delegate.getReplyTo(), replyToObservable,
					delegate.getErrorConsumer());
		}

		@Override
		public <X> Event<X> copy(X data) {
			return new ReplyToEvent<X>(getHeaders(), data, getReplyTo(), replyToObservable, getErrorConsumer());
		}

		public Observable getReplyToObservable() {
			return replyToObservable;
		}
	}

	public class ReplyToConsumer<E extends Event<?>, V> implements Consumer<E> {
		private final Function<E, V> fn;

		private ReplyToConsumer(Function<E, V> fn) {
			this.fn = fn;
		}

		@Override
		public void accept(E ev) {
			Observable replyToObservable = EventBus.this;

			if (ReplyToEvent.class.isAssignableFrom(ev.getClass())) {
				Observable o = ((ReplyToEvent<?>) ev).getReplyToObservable();
				if (null != o) {
					replyToObservable = o;
				}
			}

			try {
				V reply = fn.apply(ev);

				Event<?> replyEv;
				if (null == reply) {
					replyEv = new Event<Void>(Void.class);
				} else {
					replyEv = (Event.class.isAssignableFrom(reply.getClass()) ? (Event<?>) reply : Event.wrap(reply));
				}

				replyToObservable.notify(ev.getReplyTo(), replyEv);
			} catch (Throwable x) {
				replyToObservable.notify(x.getClass(), Event.wrap(x));
			}
		}

		public Function<E, V> getDelegate() {
			return fn;
		}
	}

}
