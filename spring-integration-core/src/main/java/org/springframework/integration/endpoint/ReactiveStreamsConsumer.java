/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.endpoint;

import java.util.function.Consumer;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import org.springframework.context.Lifecycle;
import org.springframework.integration.channel.MessageChannelReactiveUtils;
import org.springframework.integration.channel.MessagePublishingErrorHandler;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.router.MessageRouter;
import org.springframework.integration.support.channel.BeanFactoryChannelResolver;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.util.Assert;
import org.springframework.util.ErrorHandler;

import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;


/**
 * @author Artem Bilan
 * @since 5.0
 */
public class ReactiveStreamsConsumer extends AbstractEndpoint implements IntegrationConsumer {

	private final MessageChannel inputChannel;

	private final MessageHandler messageHandler;

	private final Publisher<Message<Object>> publisher;

	private final Subscriber<Message<?>> subscriber;

	private final Lifecycle lifecycleDelegate;

	private ErrorHandler errorHandler;

	private volatile Subscription subscription;

	@SuppressWarnings("unchecked")
	public ReactiveStreamsConsumer(MessageChannel inputChannel, MessageHandler messageHandler) {
		this(inputChannel,
				messageHandler instanceof Subscriber
						? (Subscriber<Message<?>>) messageHandler
						: new MessageHandlerSubscriber(messageHandler));
	}

	public ReactiveStreamsConsumer(MessageChannel inputChannel, final Subscriber<Message<?>> subscriber) {
		this.inputChannel = inputChannel;
		Assert.notNull(inputChannel, "'inputChannel' must not be null");
		Assert.notNull(subscriber, "'subscriber' must not be null");

		this.publisher = MessageChannelReactiveUtils.toPublisher(inputChannel);
		this.subscriber = subscriber;
		this.lifecycleDelegate = subscriber instanceof Lifecycle ? (Lifecycle) subscriber : null;
		if (subscriber instanceof MessageHandlerSubscriber) {
			this.messageHandler = ((MessageHandlerSubscriber) subscriber).messageHandler;
		}
		else {
			this.messageHandler = this.subscriber::onNext;
		}
	}

	public void setErrorHandler(ErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
	}

	@Override
	public MessageChannel getInputChannel() {
		return this.inputChannel;
	}

	@Override
	public MessageChannel getOutputChannel() {
		if (this.messageHandler instanceof MessageProducer) {
			return ((MessageProducer) this.messageHandler).getOutputChannel();
		}
		else if (this.messageHandler instanceof MessageRouter) {
			return ((MessageRouter) this.messageHandler).getDefaultOutputChannel();
		}
		else {
			return null;
		}
	}

	@Override
	public MessageHandler getHandler() {
		return this.messageHandler;
	}

	@Override
	protected void onInit() throws Exception {
		super.onInit();
		if (this.errorHandler == null) {
			Assert.notNull(getBeanFactory(), "BeanFactory is required");
			this.errorHandler = new MessagePublishingErrorHandler(new BeanFactoryChannelResolver(getBeanFactory()));
		}
	}

	@Override
	protected void doStart() {
		if (this.lifecycleDelegate != null) {
			this.lifecycleDelegate.start();
		}
		this.publisher.subscribe(new BaseSubscriber<Message<?>>() {

			private final Subscriber<Message<?>> delegate = ReactiveStreamsConsumer.this.subscriber;

			public void hookOnSubscribe(Subscription s) {
				this.delegate.onSubscribe(s);
				ReactiveStreamsConsumer.this.subscription = s;
			}

			public void hookOnNext(Message<?> message) {
				try {
					this.delegate.onNext(message);
				}
				catch (Exception e) {
					ReactiveStreamsConsumer.this.errorHandler.handleError(e);
					hookOnError(e);
				}
			}

			public void hookOnError(Throwable t) {
				this.delegate.onError(t);
			}

			public void hookOnComplete() {
				this.delegate.onComplete();
			}

		});
	}

	@Override
	protected void doStop() {
		if (this.subscription != null) {
			this.subscription.cancel();
		}
		if (this.lifecycleDelegate != null) {
			this.lifecycleDelegate.stop();
		}
	}


	private static final class MessageHandlerSubscriber
			implements Subscriber<Message<?>>, Disposable, Lifecycle {

		private final Consumer<Message<?>> consumer;

		private Subscription subscription;

		private MessageHandler messageHandler;

		MessageHandlerSubscriber(MessageHandler messageHandler) {
			Assert.notNull(messageHandler, "'messageHandler' must not be null");
			this.messageHandler = messageHandler;
			this.consumer = this.messageHandler::handleMessage;
		}

		@Override
		public void onSubscribe(Subscription s) {
			this.subscription = s;
			s.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(Message<?> message) {
			this.consumer.accept(message);
		}

		@Override
		public void onError(Throwable t) {
		}

		@Override
		public void onComplete() {
			dispose();
		}

		@Override
		public void dispose() {
			Subscription s = this.subscription;
			if (s != null) {
				this.subscription = null;
				s.cancel();
			}
		}

		@Override
		public boolean isDisposed() {
			return this.subscription == null;
		}


		@Override
		public void start() {
			if (this.messageHandler instanceof Lifecycle) {
				((Lifecycle) this.messageHandler).start();
			}
		}

		@Override
		public void stop() {
			if (this.messageHandler instanceof Lifecycle) {
				((Lifecycle) this.messageHandler).stop();
			}
		}

		@Override
		public boolean isRunning() {
			return !(this.messageHandler instanceof Lifecycle) || ((Lifecycle) this.messageHandler).isRunning();
		}

	}

}
