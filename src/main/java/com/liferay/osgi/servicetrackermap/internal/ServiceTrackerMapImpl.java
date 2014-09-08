/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.osgi.servicetrackermap.internal;

import com.liferay.osgi.servicetrackermap.ServiceReferenceMapper;
import com.liferay.osgi.servicetrackermap.ServiceReferenceServiceTuple;
import com.liferay.osgi.servicetrackermap.ServiceTrackerBucket;
import com.liferay.osgi.servicetrackermap.ServiceTrackerBucketFactory;
import com.liferay.osgi.servicetrackermap.ServiceTrackerMap;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Carlos Sierra Andrés
 */
public class ServiceTrackerMapImpl<K, SR, TS, R>
	implements ServiceTrackerMap<K, R> {

	public ServiceTrackerMapImpl(
            BundleContext bundleContext, Class<SR> clazz, String filterString,
            ServiceReferenceMapper<K, SR> serviceReferenceMapper,
            ServiceTrackerCustomizer<SR, TS> serviceTrackerCustomizer,
            ServiceTrackerBucketFactory<SR, TS, R> serviceTrackerMapBucketFactory)
        throws InvalidSyntaxException {

		_serviceReferenceMapper = serviceReferenceMapper;
		_serviceTrackerCustomizer = serviceTrackerCustomizer;
		_serviceTrackerMapBucketFactory = serviceTrackerMapBucketFactory;

		if (filterString != null) {
			Filter filter = bundleContext.createFilter("(&(objectClass=" + clazz.getName() + ")" + filterString + ")");

			_serviceTracker = new ServiceTracker<>(
                bundleContext, filter, new ServiceReferenceServiceTrackerCustomizer());
		}
		else {
            _serviceTracker = new ServiceTracker<>(
                bundleContext, clazz, new ServiceReferenceServiceTrackerCustomizer());
		}
	}

	@Override
	public void close() {
		_serviceTracker.close();
	}

	@Override
	public R getService(K key) {
		ServiceTrackerBucket<SR, TS, R> serviceTrackerBucket = _serviceTrackerBuckets.get(key);

		if (serviceTrackerBucket == null) {
			return null;
		}

		return serviceTrackerBucket.getContent();
	}

	@Override
	public void open() {
		_serviceTracker.open();
	}

	private ServiceReferenceMapper<K, SR> _serviceReferenceMapper;
	private ServiceTracker<SR, ServiceReferenceServiceTuple<SR, TS>> _serviceTracker;
	private ConcurrentHashMap<K, ServiceTrackerBucket<SR, TS, R>> _serviceTrackerBuckets = new ConcurrentHashMap<K, ServiceTrackerBucket<SR, TS, R>>();
	private ServiceTrackerCustomizer<SR, TS> _serviceTrackerCustomizer;
	private ServiceTrackerBucketFactory<SR, TS, R> _serviceTrackerMapBucketFactory;

	private class Holder<T> {

		public T get() {
			return _value;
		}

		public void set(T value) {
			_value = value;
		}

		private T _value;

	}

	private class ServiceReferenceServiceTrackerCustomizer
        implements ServiceTrackerCustomizer<SR, ServiceReferenceServiceTuple<SR, TS>> {

		@Override
		public ServiceReferenceServiceTuple<SR, TS> addingService(final ServiceReference<SR> serviceReference) {

			final Holder<ServiceReferenceServiceTuple<SR, TS>> holder = new Holder<ServiceReferenceServiceTuple<SR, TS>>();

			_serviceReferenceMapper.map(serviceReference,
                new ServiceReferenceMapper.Emitter<K>() {

					@Override
					public void emit(K key) {
						ServiceTrackerBucket<SR, TS, R> serviceTrackerBucket = _serviceTrackerBuckets.get(key);

						if (serviceTrackerBucket == null) {
							ServiceTrackerBucket<SR, TS, R> newServiceTrackerBucket =
                                _serviceTrackerMapBucketFactory.create();

							serviceTrackerBucket = _serviceTrackerBuckets.putIfAbsent(key, newServiceTrackerBucket);

							if (serviceTrackerBucket == null) {
								serviceTrackerBucket = newServiceTrackerBucket;
							}
						}

						ServiceReferenceServiceTuple<SR, TS> serviceReferenceServiceTuple = holder.get();

						if (serviceReferenceServiceTuple == null) {
							TS service = _serviceTrackerCustomizer.addingService(serviceReference);

							serviceReferenceServiceTuple = new ServiceReferenceServiceTuple<>(
                                serviceReference, service);

							holder.set(serviceReferenceServiceTuple);
						}

						serviceTrackerBucket.store(serviceReferenceServiceTuple);
					}
				});

			return holder.get();
		}

		@Override
		public void modifiedService(
            ServiceReference<SR> service, ServiceReferenceServiceTuple<SR, TS> serviceReferenceServiceTuple) {

			_serviceTrackerCustomizer.modifiedService(service, serviceReferenceServiceTuple.getService());
		}

		@Override
		public void removedService(
            final ServiceReference<SR> serviceReference,
            final ServiceReferenceServiceTuple<SR, TS> serviceReferenceServiceTuple) {

			_serviceReferenceMapper.map(serviceReference,
				new ServiceReferenceMapper.Emitter<K>() {

					@Override
					public void emit(K key) {
						ServiceTrackerBucket<SR, TS, R> serviceTrackerBucket = _serviceTrackerBuckets.get(key);

						if (serviceTrackerBucket == null) {
							return;
						}

						serviceTrackerBucket.remove(serviceReferenceServiceTuple);

						if (serviceTrackerBucket.isDisposable()) {
							_serviceTrackerBuckets.remove(key);
						}
					}
				});

			_serviceTrackerCustomizer.removedService(serviceReference, serviceReferenceServiceTuple.getService());
		}
	}

}