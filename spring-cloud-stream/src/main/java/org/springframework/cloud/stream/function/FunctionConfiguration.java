/*
 * Copyright 2018-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.function;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.util.function.Tuples;


import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.cloud.function.context.FunctionProperties;
import org.springframework.cloud.function.context.FunctionRegistry;
import org.springframework.cloud.function.context.PollableBean;
import org.springframework.cloud.function.context.catalog.BeanFactoryAwareFunctionRegistry.FunctionInvocationWrapper;
import org.springframework.cloud.function.context.catalog.FunctionInspector;
import org.springframework.cloud.function.context.catalog.FunctionTypeUtils;
import org.springframework.cloud.function.context.config.ContextFunctionCatalogAutoConfiguration;
import org.springframework.cloud.function.context.config.FunctionContextUtils;
import org.springframework.cloud.function.context.config.RoutingFunction;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.cloud.stream.binder.BinderTypeRegistry;
import org.springframework.cloud.stream.binder.BindingCreatedEvent;
import org.springframework.cloud.stream.binder.ConsumerProperties;
import org.springframework.cloud.stream.binder.PartitionHandler;
import org.springframework.cloud.stream.binder.ProducerProperties;
import org.springframework.cloud.stream.binding.BindableProxyFactory;
import org.springframework.cloud.stream.binding.BinderAwareChannelResolver;
import org.springframework.cloud.stream.config.BinderFactoryAutoConfiguration;
import org.springframework.cloud.stream.config.BindingBeansRegistrar;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceConfiguration;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.cloud.stream.messaging.DirectWithAttributesChannel;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.type.MethodMetadata;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.integration.channel.AbstractMessageChannel;
import org.springframework.integration.channel.MessageChannelReactiveUtils;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlowBuilder;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.expression.ExpressionUtils;
import org.springframework.integration.handler.ServiceActivatingHandler;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * @author Oleg Zhurakousky
 * @author David Turanski
 * @author Ilayaperumal Gopinathan
 * @since 2.1
 */
@SuppressWarnings("deprecation")
@Configuration
@EnableConfigurationProperties(StreamFunctionProperties.class)
@Import({ BindingBeansRegistrar.class, BinderFactoryAutoConfiguration.class })
@AutoConfigureBefore(BindingServiceConfiguration.class)
@AutoConfigureAfter(ContextFunctionCatalogAutoConfiguration.class)
@ConditionalOnBean(FunctionRegistry.class)
public class FunctionConfiguration {

	@Bean
	public InitializingBean functionBindingRegistrar(Environment environment, FunctionCatalog functionCatalog,
			StreamFunctionProperties streamFunctionProperties, BinderTypeRegistry binderTypeRegistry) {
		return new FunctionBindingRegistrar(binderTypeRegistry, functionCatalog, streamFunctionProperties);
	}

	@Bean
	public InitializingBean functionInitializer(FunctionCatalog functionCatalog, FunctionInspector functionInspector,
			StreamFunctionProperties functionProperties, @Nullable BindableProxyFactory[] bindableProxyFactories,
			BindingServiceProperties serviceProperties, ConfigurableApplicationContext applicationContext,
			FunctionBindingRegistrar bindingHolder, BinderAwareChannelResolver dynamicDestinationResolver) {

		boolean shouldCreateInitializer = applicationContext.containsBean("output")
				|| ObjectUtils.isEmpty(applicationContext.getBeanNamesForAnnotation(EnableBinding.class));

		return shouldCreateInitializer
				? new FunctionToDestinationBinder(functionCatalog, functionProperties,
						serviceProperties, dynamicDestinationResolver)
						: null;

	}

	/*
	 * Binding initializer responsible only for Suppliers
	 */
	@Bean
	InitializingBean supplierInitializer(FunctionCatalog functionCatalog, StreamFunctionProperties functionProperties,
			GenericApplicationContext context, BindingServiceProperties serviceProperties,
			@Nullable BindableFunctionProxyFactory[] proxyFactories, BinderAwareChannelResolver dynamicDestinationResolver,
			TaskScheduler taskScheduler) {

		if (!ObjectUtils.isEmpty(context.getBeanNamesForAnnotation(EnableBinding.class)) || proxyFactories == null) {
			return null;
		}

		return new InitializingBean() {

			@Override
			public void afterPropertiesSet() throws Exception {
				for (BindableFunctionProxyFactory proxyFactory : proxyFactories) {
					FunctionInvocationWrapper functionWrapper = functionCatalog.lookup(proxyFactory.getFunctionDefinition());
					if (functionWrapper != null && functionWrapper.isSupplier()) {
						// gather output content types
						List<String> contentTypes = new ArrayList<String>();
						Assert.isTrue(proxyFactory.getOutputs().size() == 1, "Supplier with multiple outputs is not supported at the moment.");
						String outputName  = proxyFactory.getOutputs().iterator().next();

						BindingProperties bindingProperties = serviceProperties.getBindingProperties(outputName);
						if (!(bindingProperties.getProducer() != null && bindingProperties.getProducer().isUseNativeEncoding())) {
							contentTypes.add(bindingProperties.getContentType());
						}
						// obtain function wrapper with proper output content types
						functionWrapper = functionCatalog.lookup(proxyFactory.getFunctionDefinition(), contentTypes.toArray(new String[0]));
						Publisher<Object> beginPublishingTrigger = setupBindingTrigger(context);

						if (!functionProperties.isComposeFrom() && !functionProperties.isComposeTo()) {
							String integrationFlowName = proxyFactory.getFunctionDefinition() + "_integrationflow";
							PollableBean pollable = extractPollableAnnotation(functionProperties, context, proxyFactory);

							IntegrationFlow integrationFlow = integrationFlowFromProvidedSupplier(functionWrapper, beginPublishingTrigger,
									pollable, context, taskScheduler)
									.route(Message.class, message -> {
										if (message.getHeaders().get("spring.cloud.stream.sendto.destination") != null) {
											String destinationName = (String) message.getHeaders().get("spring.cloud.stream.sendto.destination");
											return dynamicDestinationResolver.resolveDestination(destinationName);
										}
										return outputName;
									}).get();
							IntegrationFlow postProcessedFlow = (IntegrationFlow) context.getAutowireCapableBeanFactory()
									.applyBeanPostProcessorsBeforeInitialization(integrationFlow, integrationFlowName);
							context.registerBean(integrationFlowName, IntegrationFlow.class, () -> postProcessedFlow);
						}
					}
				}
			}
		};
	}

	/*
	 * Creates a publishing trigger to ensure Supplier does not begin publishing until binding is created
	 */
	private Publisher<Object> setupBindingTrigger(GenericApplicationContext context) {
		AtomicReference<MonoSink<Object>> triggerRef = new AtomicReference<>();
		Publisher<Object> beginPublishingTrigger = Mono.create(emmiter -> {
			triggerRef.set(emmiter);
		});
		context.addApplicationListener(event -> {
			if (event instanceof BindingCreatedEvent) {
				if (triggerRef.get() != null) {
					triggerRef.get().success();
				}
			}
		});
		return beginPublishingTrigger;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private IntegrationFlowBuilder integrationFlowFromProvidedSupplier(Supplier<?> supplier,
			Publisher<Object> beginPublishingTrigger, PollableBean pollable, GenericApplicationContext context,
			TaskScheduler taskScheduler) {

		IntegrationFlowBuilder integrationFlowBuilder;
		Type functionType = ((FunctionInvocationWrapper) supplier).getFunctionType();

		boolean splittable = pollable != null
				&& (boolean) AnnotationUtils.getAnnotationAttributes(pollable).get("splittable");

		if (pollable == null && FunctionTypeUtils.isReactive(FunctionTypeUtils.getInputType(functionType, 0))) {
			Publisher publisher = (Publisher) supplier.get();
			publisher = publisher instanceof Mono
					? ((Mono) publisher).delaySubscription(beginPublishingTrigger).map(this::wrapToMessageIfNecessary)
					: ((Flux) publisher).delaySubscription(beginPublishingTrigger).map(this::wrapToMessageIfNecessary);

			integrationFlowBuilder = IntegrationFlows.from(publisher);

			// see https://github.com/spring-cloud/spring-cloud-stream/issues/1863 for details about the following code
			taskScheduler.schedule(() -> { }, Instant.now()); // will keep AC alive
		}
		else { // implies pollable
			integrationFlowBuilder = IntegrationFlows.from(supplier);
			if (splittable) {
				integrationFlowBuilder = integrationFlowBuilder.split();
			}
		}

		return integrationFlowBuilder;
	}

	private PollableBean extractPollableAnnotation(StreamFunctionProperties functionProperties, GenericApplicationContext context,
			BindableFunctionProxyFactory proxyFactory) {
		// here we need to ensure that for cases where composition is defined we only look for supplier method to find Pollable annotation.
		String supplierFunctionName = StringUtils
				.delimitedListToStringArray(proxyFactory.getFunctionDefinition().replaceAll(",", "|").trim(), "|")[0];
		BeanDefinition bd = context.getBeanDefinition(supplierFunctionName);
		if (!(bd instanceof RootBeanDefinition)) {
			return null;
		}

		Method factoryMethod = ((RootBeanDefinition) bd).getResolvedFactoryMethod();
		if (factoryMethod == null) {
			Object source = bd.getSource();
			if (source instanceof MethodMetadata) {
				Class<?> factory = ClassUtils.resolveClassName(((MethodMetadata) source).getDeclaringClassName(), null);
				Class<?>[] params = FunctionContextUtils.getParamTypesFromBeanDefinitionFactory(factory, (RootBeanDefinition) bd);
				factoryMethod = ReflectionUtils.findMethod(factory, ((MethodMetadata) source).getMethodName(), params);
			}
		}
		Assert.notNull(factoryMethod, "Failed to introspect factory method since it was not discovered for function '"
				+ functionProperties.getDefinition() + "'");
		return factoryMethod.getReturnType().isAssignableFrom(Supplier.class)
				? AnnotationUtils.findAnnotation(factoryMethod, PollableBean.class)
						: null;
	}


	@SuppressWarnings("unchecked")
	private <T> Message<T> wrapToMessageIfNecessary(T value) {
		return value instanceof Message
				? (Message<T>) value
						: MessageBuilder.withPayload(value).build();
	}

	private static class FunctionToDestinationBinder implements InitializingBean, ApplicationContextAware {

		private GenericApplicationContext applicationContext;

		private BindableProxyFactory[] bindableProxyFactories;

		private final FunctionCatalog functionCatalog;

		private final StreamFunctionProperties functionProperties;

		private final BindingServiceProperties serviceProperties;

		private final BinderAwareChannelResolver dynamicDestinationResolver;

		FunctionToDestinationBinder(FunctionCatalog functionCatalog, StreamFunctionProperties functionProperties,
				BindingServiceProperties serviceProperties, BinderAwareChannelResolver dynamicDestinationResolver) {
			this.functionCatalog = functionCatalog;
			this.functionProperties = functionProperties;
			this.serviceProperties = serviceProperties;
			this.dynamicDestinationResolver = dynamicDestinationResolver;
		}

		@Override
		public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
			this.applicationContext = (GenericApplicationContext) applicationContext;
		}

		@Override
		public void afterPropertiesSet() throws Exception {
			Map<String, BindableProxyFactory> beansOfType = applicationContext.getBeansOfType(BindableProxyFactory.class);
			this.bindableProxyFactories = beansOfType.values().toArray(new BindableProxyFactory[0]);
			for (BindableProxyFactory bindableProxyFactory : this.bindableProxyFactories) {
				String functionDefinition = bindableProxyFactory instanceof BindableFunctionProxyFactory
						? ((BindableFunctionProxyFactory) bindableProxyFactory).getFunctionDefinition()
								: this.functionProperties.getDefinition();
				FunctionInvocationWrapper function = functionCatalog.lookup(functionDefinition);
				if (function != null && !function.isSupplier()) {
					this.bindFunctionToDestinations(bindableProxyFactory, functionDefinition);
				}
			}
		}

		@SuppressWarnings({ "rawtypes", "unchecked" })
		private void bindFunctionToDestinations(BindableProxyFactory bindableProxyFactory, String functionDefinition) {
			this.assertBindingIsPossible(bindableProxyFactory);

			Set<String> inputBindingNames = bindableProxyFactory.getInputs();
			Set<String> outputBindingNames = bindableProxyFactory.getOutputs();

			String[] outputContentTypes = outputBindingNames.stream()
					.map(bindingName -> this.serviceProperties.getBindings().get(bindingName).getContentType())
					.toArray(String[]::new);

			FunctionInvocationWrapper function = this.functionCatalog.lookup(functionDefinition, outputContentTypes);
			Type functionType = function.getFunctionType();
			this.assertSupportedSignatures(bindableProxyFactory, functionType);

			if (isReactiveOrMultipleInputOutput(bindableProxyFactory, functionType)) {
				Publisher[] inputPublishers = inputBindingNames.stream().map(inputBindingName -> {
					SubscribableChannel inputChannel = this.applicationContext.getBean(inputBindingName, SubscribableChannel.class);
					return MessageChannelReactiveUtils.toPublisher(inputChannel);
				}).toArray(Publisher[]::new);
				Object resultPublishers = function.apply(inputPublishers.length == 1 ? inputPublishers[0] : Tuples.fromArray(inputPublishers));
				if (resultPublishers instanceof Iterable) {
					Iterator<String> outputBindingIter = outputBindingNames.iterator();
					((Iterable) resultPublishers).forEach(publisher -> {
						MessageChannel outputChannel = this.applicationContext.getBean(outputBindingIter.next(), MessageChannel.class);
						Flux.from((Publisher) publisher).doOnNext(message -> outputChannel.send((Message) message)).subscribe();
					});
				}
				else {
					outputBindingNames.stream().forEach(outputBindingName -> {
						MessageChannel outputChannel = this.applicationContext.getBean(outputBindingName, MessageChannel.class);
						Flux.from((Publisher) resultPublishers).doOnNext(message -> outputChannel.send((Message) message)).subscribe();
					});
				}
			}
			else {
				String outputDestinationName = this.determineOutputDestinationName(0, bindableProxyFactory, functionType);
				this.adjustFunctionForNativeEncodingIfNecessary(outputDestinationName, function, 0);
				if (this.functionProperties.isComposeFrom()) {
					SubscribableChannel outputChannel = this.applicationContext.getBean(outputDestinationName, SubscribableChannel.class);
//					logger.info("Composing at the head of 'output' channel");
					String outputChannelName = ((AbstractMessageChannel) outputChannel).getBeanName();
					ServiceActivatingHandler handler = createFunctionHandler(function, null, outputChannelName);

					DirectWithAttributesChannel newOutputChannel = new DirectWithAttributesChannel();
					newOutputChannel.setAttribute("type", "output");
					newOutputChannel.setComponentName("output.extended");
					this.applicationContext.registerBean("output.extended", MessageChannel.class, () -> newOutputChannel);
					bindableProxyFactory.replaceOutputChannel(outputChannelName, "output.extended", newOutputChannel);

					handler.setOutputChannelName("output.extended");
					outputChannel.subscribe(handler);
				}
				else {
					String inputDestinationName = inputBindingNames.iterator().next();
					Object inputDestination = this.applicationContext.getBean(inputDestinationName);
					if (inputDestination != null && inputDestination instanceof SubscribableChannel) {
						ServiceActivatingHandler handler = createFunctionHandler(function, inputDestinationName, outputDestinationName);
						if (!FunctionTypeUtils.isConsumer(function.getFunctionType())) {
							handler.setOutputChannelName(outputDestinationName);
						}
						((SubscribableChannel) inputDestination).subscribe(handler);
					}
				}
			}
		}

		private void adjustFunctionForNativeEncodingIfNecessary(String outputDestinationName, FunctionInvocationWrapper function, int index) {
			if (function.isConsumer()) {
				return;
			}
			BindingProperties properties = this.serviceProperties.getBindingProperties(outputDestinationName);
			if (properties.getProducer() != null && properties.getProducer().isUseNativeEncoding()) {
				Field acceptedOutputMimeTypesField = ReflectionUtils
						.findField(FunctionInvocationWrapper.class, "acceptedOutputMimeTypes", String[].class);
				acceptedOutputMimeTypesField.setAccessible(true);
				try {
					String[] acceptedOutputMimeTypes = (String[]) acceptedOutputMimeTypesField.get(function);
					acceptedOutputMimeTypes[index] = "";
				}
				catch (Exception e) {
					// ignore
				}
			}
		}

		private ServiceActivatingHandler createFunctionHandler(FunctionInvocationWrapper function,
				String inputChannelName, String outputChannelName) {
			ConsumerProperties consumerProperties = StringUtils.hasText(inputChannelName)
					? this.serviceProperties.getBindingProperties(inputChannelName).getConsumer()
							: null;
			ProducerProperties producerProperties = StringUtils.hasText(outputChannelName)
					? this.serviceProperties.getBindingProperties(outputChannelName).getProducer()
							: null;
			ServiceActivatingHandler handler = new ServiceActivatingHandler(new FunctionWrapper(function, consumerProperties,
					producerProperties, applicationContext)) {
				@Override
				protected void sendOutputs(Object result, Message<?> requestMessage) {
					if (result instanceof Message && ((Message<?>) result).getHeaders().get("spring.cloud.stream.sendto.destination") != null) {
						String destinationName = (String) ((Message<?>) result).getHeaders().get("spring.cloud.stream.sendto.destination");
						MessageChannel outputChannel = dynamicDestinationResolver.resolveDestination(destinationName);
						if (logger.isInfoEnabled()) {
							logger.info("Output message is sent to '" + destinationName + "' destination");
						}
						outputChannel.send(((Message<?>) result));
					}
					else {
						super.sendOutputs(result, requestMessage);
					}
				}
			};
			handler.setBeanFactory(this.applicationContext);
			handler.afterPropertiesSet();
			return handler;
		}

		private boolean isReactiveOrMultipleInputOutput(BindableProxyFactory bindableProxyFactory, Type functionType) {
			return isMultipleInputOutput(bindableProxyFactory)
					|| (FunctionTypeUtils.isReactive(FunctionTypeUtils.getInputType(functionType, 0))
							&& StringUtils.hasText(this.determineOutputDestinationName(0, bindableProxyFactory, functionType)));
		}

		private String determineOutputDestinationName(int index, BindableProxyFactory bindableProxyFactory, Type functionType) {
			String outputDestinationName = bindableProxyFactory instanceof BindableFunctionProxyFactory
					? ((BindableFunctionProxyFactory) bindableProxyFactory).getOutputName(index)
							: (FunctionTypeUtils.isConsumer(functionType) ? null : "output");
			return outputDestinationName;
		}

		private void assertBindingIsPossible(BindableProxyFactory bindableProxyFactory) {
			if (this.isMultipleInputOutput(bindableProxyFactory)) {
				Assert.isTrue(!functionProperties.isComposeTo() && !functionProperties.isComposeFrom(),
						"Composing to/from existing Sinks and Sources are not supported for functions with multiple arguments.");
			}
		}

		private boolean isMultipleInputOutput(BindableProxyFactory bindableProxyFactory) {
			return bindableProxyFactory instanceof BindableFunctionProxyFactory
					&& ((BindableFunctionProxyFactory) bindableProxyFactory).isMultiple();
		}

		private void assertSupportedSignatures(BindableProxyFactory bindableProxyFactory, Type functionType) {
			if (this.isMultipleInputOutput(bindableProxyFactory)) {
				Assert.isTrue(!FunctionTypeUtils.isConsumer(functionType),
						"Function '" + functionProperties.getDefinition() + "' is a Consumer which is not supported "
								+ "for multi-in/out reactive streams. Only Functions are supported");
				Assert.isTrue(!FunctionTypeUtils.isSupplier(functionType),
						"Function '" + functionProperties.getDefinition() + "' is a Supplier which is not supported "
								+ "for multi-in/out reactive streams. Only Functions are supported");
				Assert.isTrue(!FunctionTypeUtils.isInputArray(functionType) && !FunctionTypeUtils.isOutputArray(functionType),
						"Function '" + functionProperties.getDefinition() + "' has the following signature: ["
						+ functionType + "]. Your input and/or outout lacks arity and therefore we "
								+ "can not determine how many input/output destinations are required in the context of "
								+ "function input/output binding.");

				int inputCount = FunctionTypeUtils.getInputCount(functionType);
				for (int i = 0; i < inputCount; i++) {
					Assert.isTrue(FunctionTypeUtils.isReactive(FunctionTypeUtils.getInputType(functionType, i)),
							"Function '" + functionProperties.getDefinition() + "' has the following signature: ["
									+ functionType + "]. Non-reactive functions with multiple "
									+ "inputs/outputs are not supported in the context of Spring Cloud Stream.");
				}
				int outputCount = FunctionTypeUtils.getOutputCount(functionType);
				for (int i = 0; i < outputCount; i++) {
					Assert.isTrue(FunctionTypeUtils.isReactive(FunctionTypeUtils.getInputType(functionType, i)),
							"Function '" + functionProperties.getDefinition() + "' has the following signature: ["
									+ functionType + "]. Non-reactive functions with multiple "
									+ "inputs/outputs are not supported in the context of Spring Cloud Stream.");
				}
			}
		}

	}

	/**
	 *
	 * It's signatures ensures that within the context of s-c-stream Spring Integration does
	 * not attempt any conversion and sends a raw Message.
	 */
	@SuppressWarnings("rawtypes")
	private static class FunctionWrapper implements Function<Message<byte[]>, Object> {
		private final Function function;

		private final ConsumerProperties consumerProperties;

		@SuppressWarnings("unused")
		private final ProducerProperties producerProperties;

		private final Field headersField;

		private final ConfigurableApplicationContext applicationContext;

		FunctionWrapper(Function function, ConsumerProperties consumerProperties,
				ProducerProperties producerProperties, ConfigurableApplicationContext applicationContext) {
			this.function = function;
			Type type =  ((FunctionInvocationWrapper) function).getFunctionType();
			if (FunctionTypeUtils.isReactive(FunctionTypeUtils.getOutputType(type, 0))) {
				throw new IllegalStateException("Functions with mixed semantics (imperative input vs. reactive output) ar not supported at the moment");
			}
			this.consumerProperties = consumerProperties;
			this.producerProperties = producerProperties;
			this.headersField = ReflectionUtils.findField(MessageHeaders.class, "headers");
			this.headersField.setAccessible(true);
			this.applicationContext = applicationContext;
		}

		@SuppressWarnings("unchecked")
		@Override
		public Object apply(Message<byte[]> message) {
			if (message != null && consumerProperties != null) {
				Map<String, Object> headersMap = (Map<String, Object>) ReflectionUtils
						.getField(this.headersField, message.getHeaders());
				headersMap.put(FunctionProperties.SKIP_CONVERSION_HEADER, consumerProperties.isUseNativeDecoding());
			}

			Function<Message, Message> outputMessageEnricher = null;
			if (producerProperties != null && producerProperties.isPartitioned()) {
				StandardEvaluationContext evaluationContext = ExpressionUtils.createStandardEvaluationContext(this.applicationContext.getBeanFactory());
				PartitionHandler partitionHandler = new PartitionHandler(evaluationContext, producerProperties, this.applicationContext.getBeanFactory());

				outputMessageEnricher = outputMessage -> {
					int partitionId = partitionHandler.determinePartition(outputMessage);
					return MessageBuilder
						.fromMessage(outputMessage)
						.setHeader(BinderHeaders.PARTITION_HEADER, partitionId).build();
				};
			}

			Object result = ((FunctionInvocationWrapper) function).apply(message, outputMessageEnricher);
			if (result instanceof Publisher && ((FunctionInvocationWrapper) this.function).getTarget() instanceof RoutingFunction) {
				throw new IllegalStateException("Routing to functions that return Publisher "
						+ "is not supported in the context of Spring Cloud Stream.");
			}
			return result;
		}
	}

	/**
	 * Creates and registers instances of BindableFunctionProxyFactory for each user defined function
	 * thus triggering destination bindings between function arguments and destinations.
	 */
	private static class FunctionBindingRegistrar implements InitializingBean, ApplicationContextAware, EnvironmentAware {

		protected final Log logger = LogFactory.getLog(getClass());

		private final BinderTypeRegistry binderTypeRegistry;

		private final FunctionCatalog functionCatalog;

		private final StreamFunctionProperties streamFunctionProperties;

		private ConfigurableApplicationContext applicationContext;

		private Environment environment;

		private int inputCount;

		private int outputCount;

		FunctionBindingRegistrar(BinderTypeRegistry binderTypeRegistry, FunctionCatalog functionCatalog, StreamFunctionProperties streamFunctionProperties) {
			this.binderTypeRegistry = binderTypeRegistry;
			this.functionCatalog = functionCatalog;
			this.streamFunctionProperties = streamFunctionProperties;
		}

		@Override
		public void afterPropertiesSet() throws Exception {
			if (ObjectUtils.isEmpty(applicationContext.getBeanNamesForAnnotation(EnableBinding.class))
					&& this.determineFunctionName(functionCatalog, environment)) {
				BeanDefinitionRegistry registry = (BeanDefinitionRegistry) applicationContext.getBeanFactory();
				String[] functionDefinitions = streamFunctionProperties.getDefinition().split(";");
				for (String functionDefinition : functionDefinitions) {
					RootBeanDefinition functionBindableProxyDefinition = new RootBeanDefinition(BindableFunctionProxyFactory.class);
					FunctionInvocationWrapper function = functionCatalog.lookup(functionDefinition);
					if (function != null) {
						Type functionType = function.getFunctionType();
						if (function.isSupplier()) {
							this.inputCount = 0;
							this.outputCount = FunctionTypeUtils.getOutputCount(functionType);
						}
						else if (function.isConsumer()) {
							this.inputCount = FunctionTypeUtils.getInputCount(functionType);
							this.outputCount = 0;
						}
						else {
							this.inputCount = FunctionTypeUtils.getInputCount(functionType);
							this.outputCount = FunctionTypeUtils.getOutputCount(functionType);
						}

						functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(functionDefinition);
						functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(this.inputCount);
						functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(this.outputCount);
						functionBindableProxyDefinition.getConstructorArgumentValues().addGenericArgumentValue(streamFunctionProperties);
						registry.registerBeanDefinition(functionDefinition + "_binding", functionBindableProxyDefinition);
					}
				}
			}
			else {
				logger.info("Functional binding is disabled due to the presense of @EnableBinding annotation in your configuration");
			}
		}

		private boolean determineFunctionName(FunctionCatalog catalog, Environment environment) {
			String definition = streamFunctionProperties.getDefinition();
			if (!StringUtils.hasText(definition)) {
				definition = environment.getProperty("spring.cloud.function.definition");
			}

			if (StringUtils.hasText(definition)) {
				streamFunctionProperties.setDefinition(definition);
			}
			else if (Boolean.parseBoolean(environment.getProperty("spring.cloud.stream.function.routing.enabled", "false"))
					|| environment.containsProperty("spring.cloud.function.routing-expression")) {
				streamFunctionProperties.setDefinition(RoutingFunction.FUNCTION_NAME);
			}
			else {
				streamFunctionProperties.setDefinition(((FunctionInspector) functionCatalog).getName(functionCatalog.lookup("")));
			}
			return StringUtils.hasText(streamFunctionProperties.getDefinition());
		}

		@Override
		public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
			this.applicationContext = (ConfigurableApplicationContext) applicationContext;
		}

		@Override
		public void setEnvironment(Environment environment) {
			this.environment = environment;
		}
	}
}
