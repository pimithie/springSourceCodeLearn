/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.web.reactive.result.method.annotation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import reactor.core.publisher.Mono;

import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.lang.Nullable;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolverSupport;
import org.springframework.web.server.ServerErrorException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/**
 * Abstract base class for resolving method arguments from a named value.
 * Request parameters, request headers, and path variables are examples of named
 * values. Each may have a name, a required flag, and a default value.
 *
 * <p>Subclasses define how to do the following:
 * <ul>
 * <li>Obtain named value information for a method parameter
 * <li>Resolve names into argument values
 * <li>Handle missing argument values when argument values are required
 * <li>Optionally handle a resolved value
 * </ul>
 *
 * <p>A default value string can contain ${...} placeholders and Spring Expression
 * Language #{...} expressions. For this to work a
 * {@link ConfigurableBeanFactory} must be supplied to the class constructor.
 *
 * @author Rossen Stoyanchev
 * @since 5.0
 */
public abstract class AbstractNamedValueArgumentResolver extends HandlerMethodArgumentResolverSupport {

	@Nullable
	// 容器
	private final ConfigurableBeanFactory configurableBeanFactory;

	@Nullable
	private final BeanExpressionContext expressionContext;

	// MethodParameter与NamedValueInfo的缓存映射
	private final Map<MethodParameter, NamedValueInfo> namedValueInfoCache = new ConcurrentHashMap<>(256);


	/**
	 * @param factory a bean factory to use for resolving ${...} placeholder
	 * and #{...} SpEL expressions in default values, or {@code null} if default
	 * values are not expected to contain expressions
	 * @param registry for checking reactive type wrappers
	 */
	public AbstractNamedValueArgumentResolver(@Nullable ConfigurableBeanFactory factory,
			ReactiveAdapterRegistry registry) {
		
		super(registry);
		this.configurableBeanFactory = factory;
		this.expressionContext = (factory != null ? new BeanExpressionContext(factory, null) : null);
	}


	@Override
	public Mono<Object> resolveArgument(
			MethodParameter parameter, BindingContext bindingContext, ServerWebExchange exchange) {

		// 获取NamedValueInfo
		NamedValueInfo namedValueInfo = getNamedValueInfo(parameter);
		// 获取内嵌参数，若无内嵌，则仍然使用本身
		MethodParameter nestedParameter = parameter.nestedIfOptional();

		// 解析注解申明的value，占位符和表达式
		Object resolvedName = resolveStringValue(namedValueInfo.name);
		if (resolvedName == null) {
			return Mono.error(new IllegalArgumentException(
					"Specified name must not resolve to null: [" + namedValueInfo.name + "]"));
		}

		Model model = bindingContext.getModel();

		return resolveName(resolvedName.toString(), nestedParameter, exchange)
				.flatMap(arg -> {
					if ("".equals(arg) && namedValueInfo.defaultValue != null) {
						arg = resolveStringValue(namedValueInfo.defaultValue);
					}
					arg = applyConversion(arg, namedValueInfo, parameter, bindingContext, exchange);
					handleResolvedValue(arg, namedValueInfo.name, parameter, model, exchange);
					return Mono.justOrEmpty(arg);
				})
				.switchIfEmpty(getDefaultValue(
						namedValueInfo, parameter, bindingContext, model, exchange));
	}

	/**
	 * Obtain the named value for the given method parameter.
	 */
	private NamedValueInfo getNamedValueInfo(MethodParameter parameter) {
		// 先从缓存获取
		NamedValueInfo namedValueInfo = this.namedValueInfoCache.get(parameter);
		// 若未从缓存中获取到，则进行创建
		if (namedValueInfo == null) {
			// createNamedValueInfo抽象方法，由子类实现
			namedValueInfo = createNamedValueInfo(parameter);
			namedValueInfo = updateNamedValueInfo(parameter, namedValueInfo);
			// 放入缓存
			this.namedValueInfoCache.put(parameter, namedValueInfo);
		}
		return namedValueInfo;
	}

	/**
	 * Create the {@link NamedValueInfo} object for the given method parameter.
	 * Implementations typically retrieve the method annotation by means of
	 * {@link MethodParameter#getParameterAnnotation(Class)}.
	 * @param parameter the method parameter
	 * @return the named value information
	 */
	protected abstract NamedValueInfo createNamedValueInfo(MethodParameter parameter);

	/**
	 * Create a new NamedValueInfo based on the given NamedValueInfo with
	 * sanitized values.
	 */
	private NamedValueInfo updateNamedValueInfo(MethodParameter parameter, NamedValueInfo info) {
		String name = info.name;
		// 若NamedValueInfo中name属性为空
		if (info.name.isEmpty()) {
			// 则取参数名(parameter name)为其name属性
			name = parameter.getParameterName();
			if (name == null) {
				String type = parameter.getNestedParameterType().getName();
				throw new IllegalArgumentException("Name for argument type [" + type + "] not " +
						"available, and parameter name information not found in class file either.");
			}
		}
		// 获取默认值
		String defaultValue = (ValueConstants.DEFAULT_NONE.equals(info.defaultValue) ? null : info.defaultValue);
		// 创建NamedValueInfo对象
		return new NamedValueInfo(name, info.required, defaultValue);
	}

	/**
	 * Resolve the given annotation-specified value,
	 * potentially containing placeholders and expressions.
	 */
	@Nullable
	private Object resolveStringValue(String value) {
		if (this.configurableBeanFactory == null || this.expressionContext == null) {
			return value;
		}
		String placeholdersResolved = this.configurableBeanFactory.resolveEmbeddedValue(value);
		BeanExpressionResolver exprResolver = this.configurableBeanFactory.getBeanExpressionResolver();
		if (exprResolver == null) {
			return value;
		}
		return exprResolver.evaluate(placeholdersResolved, this.expressionContext);
	}

	/**
	 * Resolve the given parameter type and value name into an argument value.
	 * @param name the name of the value being resolved
	 * @param parameter the method parameter to resolve to an argument value
	 * (pre-nested in case of a {@link java.util.Optional} declaration)
	 * @param exchange the current exchange
	 * @return the resolved argument (may be empty {@link Mono})
	 */
	protected abstract Mono<Object> resolveName(String name, MethodParameter parameter, ServerWebExchange exchange);

	/**
	 * Apply type conversion if necessary.
	 */
	@Nullable
	private Object applyConversion(@Nullable Object value, NamedValueInfo namedValueInfo, MethodParameter parameter,
			BindingContext bindingContext, ServerWebExchange exchange) {

		WebDataBinder binder = bindingContext.createDataBinder(exchange, namedValueInfo.name);
		try {
			value = binder.convertIfNecessary(value, parameter.getParameterType(), parameter);
		}
		catch (ConversionNotSupportedException ex) {
			throw new ServerErrorException("Conversion not supported.", parameter, ex);
		}
		catch (TypeMismatchException ex) {
			throw new ServerWebInputException("Type mismatch.", parameter, ex);
		}
		return value;
	}

	/**
	 * Resolve the default value, if any.
	 */
	private Mono<Object> getDefaultValue(NamedValueInfo namedValueInfo, MethodParameter parameter,
			BindingContext bindingContext, Model model, ServerWebExchange exchange) {

		return Mono.fromSupplier(() -> {
			Object value = null;
			if (namedValueInfo.defaultValue != null) {
				value = resolveStringValue(namedValueInfo.defaultValue);
			}
			else if (namedValueInfo.required && !parameter.isOptional()) {
				handleMissingValue(namedValueInfo.name, parameter, exchange);
			}
			value = handleNullValue(namedValueInfo.name, value, parameter.getNestedParameterType());
			value = applyConversion(value, namedValueInfo, parameter, bindingContext, exchange);
			handleResolvedValue(value, namedValueInfo.name, parameter, model, exchange);
			return value;
		});
	}

	/**
	 * Invoked when a named value is required, but
	 * {@link #resolveName(String, MethodParameter, ServerWebExchange)} returned
	 * {@code null} and there is no default value. Subclasses typically throw an
	 * exception in this case.
	 * @param name the name for the value
	 * @param parameter the method parameter
	 * @param exchange the current exchange
	 */
	@SuppressWarnings("UnusedParameters")
	protected void handleMissingValue(String name, MethodParameter parameter, ServerWebExchange exchange) {
		handleMissingValue(name, parameter);
	}

	/**
	 * Invoked when a named value is required, but
	 * {@link #resolveName(String, MethodParameter, ServerWebExchange)} returned
	 * {@code null} and there is no default value. Subclasses typically throw an
	 * exception in this case.
	 * @param name the name for the value
	 * @param parameter the method parameter
	 */
	protected void handleMissingValue(String name, MethodParameter parameter) {
		String typeName = parameter.getNestedParameterType().getSimpleName();
		throw new ServerWebInputException("Missing argument '" + name + "' for method " +
				"parameter of type " + typeName, parameter);
	}

	/**
	 * A {@code null} results in a {@code false} value for {@code boolean}s or
	 * an exception for other primitives.
	 */
	@Nullable
	private Object handleNullValue(String name, @Nullable Object value, Class<?> paramType) {
		if (value == null) {
			if (Boolean.TYPE.equals(paramType)) {
				return Boolean.FALSE;
			}
			else if (paramType.isPrimitive()) {
				throw new IllegalStateException("Optional " + paramType.getSimpleName() +
						" parameter '" + name + "' is present but cannot be translated into a" +
						" null value due to being declared as a primitive type. " +
						"Consider declaring it as object wrapper for the corresponding primitive type.");
			}
		}
		return value;
	}

	/**
	 * Invoked after a value is resolved.
	 * @param arg the resolved argument value
	 * @param name the argument name
	 * @param parameter the argument parameter type
	 * @param model the model
	 * @param exchange the current exchange
	 */
	@SuppressWarnings("UnusedParameters")
	protected void handleResolvedValue(
			@Nullable Object arg, String name, MethodParameter parameter, Model model, ServerWebExchange exchange) {
	}


	/**
	 * Represents the information about a named value, including name, whether
	 * it's required and a default value.
	 */
	protected static class NamedValueInfo {

		private final String name;

		private final boolean required;

		@Nullable
		private final String defaultValue;

		public NamedValueInfo(String name, boolean required, @Nullable String defaultValue) {
			this.name = name;
			this.required = required;
			this.defaultValue = defaultValue;
		}
	}

}
