/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.spi;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.ProxyFactoryConfig;
import com.hazelcast.client.proxy.*;
import com.hazelcast.collection.list.ListService;
import com.hazelcast.collection.set.SetService;
import com.hazelcast.multimap.MultiMapService;
import com.hazelcast.concurrent.atomiclong.AtomicLongService;
import com.hazelcast.concurrent.countdownlatch.CountDownLatchService;
import com.hazelcast.concurrent.idgen.IdGeneratorService;
import com.hazelcast.concurrent.lock.LockServiceImpl;
import com.hazelcast.concurrent.semaphore.SemaphoreService;
import com.hazelcast.config.ListenerConfig;
import com.hazelcast.core.DistributedObject;
import com.hazelcast.core.DistributedObjectEvent;
import com.hazelcast.core.DistributedObjectListener;
import com.hazelcast.core.IAtomicLong;
import com.hazelcast.executor.DistributedExecutorService;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.map.MapService;
import com.hazelcast.nio.ClassLoaderUtil;
import com.hazelcast.queue.QueueService;
import com.hazelcast.spi.DefaultObjectNamespace;
import com.hazelcast.spi.ObjectNamespace;
import com.hazelcast.topic.TopicService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author mdogan 5/16/13
 */
public final class ProxyManager {

    private final static ILogger logger = Logger.getLogger(ProxyManager.class);

    private final HazelcastClient client;
    private final ConcurrentMap<String, ClientProxyFactory> proxyFactories = new ConcurrentHashMap<String, ClientProxyFactory>();
    private final ConcurrentMap<ObjectNamespace, ClientProxy> proxies = new ConcurrentHashMap<ObjectNamespace, ClientProxy>();
    private final ConcurrentMap<String, DistributedObjectListener> listeners = new ConcurrentHashMap<String, DistributedObjectListener>();

    public ProxyManager(HazelcastClient client) {
        this.client = client;
        final List<ListenerConfig> listenerConfigs = client.getClientConfig().getListenerConfigs();
        if(listenerConfigs != null && !listenerConfigs.isEmpty()){
            for (ListenerConfig listenerConfig : listenerConfigs) {
                if (listenerConfig.getImplementation() instanceof DistributedObjectListener) {
                    addDistributedObjectListener((DistributedObjectListener) listenerConfig.getImplementation());
                }
            }
        }
    }

    public void init(ClientConfig config) {
        // register defaults
        register(MapService.SERVICE_NAME, new ClientProxyFactory() {
            public ClientProxy create(String id) {
                return new ClientMapProxy(MapService.SERVICE_NAME, String.valueOf(id));
            }
        });
        register(QueueService.SERVICE_NAME, new ClientProxyFactory() {
            public ClientProxy create(String id) {
                return new ClientQueueProxy(QueueService.SERVICE_NAME, String.valueOf(id));
            }
        });
        register(MultiMapService.SERVICE_NAME, new ClientProxyFactory() {
            public ClientProxy create(String id) {
                return new ClientMultiMapProxy(MultiMapService.SERVICE_NAME, String.valueOf(id));
            }
        });
        register(ListService.SERVICE_NAME, new ClientProxyFactory() {
            public ClientProxy create(String id) {
                return new ClientListProxy(ListService.SERVICE_NAME, String.valueOf(id));
            }
        });
        register(SetService.SERVICE_NAME, new ClientProxyFactory() {
            public ClientProxy create(String id) {
                return new ClientSetProxy(SetService.SERVICE_NAME, String.valueOf(id));
            }
        });
        register(SemaphoreService.SERVICE_NAME, new ClientProxyFactory() {
            public ClientProxy create(String id) {
                return new ClientSemaphoreProxy(SemaphoreService.SERVICE_NAME, String.valueOf(id));
            }
        });
        register(TopicService.SERVICE_NAME, new ClientProxyFactory() {
            public ClientProxy create(String id) {
                return new ClientTopicProxy(TopicService.SERVICE_NAME, String.valueOf(id));
            }
        });
        register(AtomicLongService.SERVICE_NAME, new ClientProxyFactory() {
            public ClientProxy create(String id) {
                return new ClientAtomicLongProxy(AtomicLongService.SERVICE_NAME, String.valueOf(id));
            }
        });
        register(DistributedExecutorService.SERVICE_NAME, new ClientProxyFactory() {
            public ClientProxy create(String id) {
                return new ClientExecutorServiceProxy(DistributedExecutorService.SERVICE_NAME, String.valueOf(id));
            }
        });
        register(LockServiceImpl.SERVICE_NAME, new ClientProxyFactory() {
            public ClientProxy create(String id) {
                return new ClientLockProxy(LockServiceImpl.SERVICE_NAME, id);
            }
        });

        register(IdGeneratorService.SERVICE_NAME, new ClientProxyFactory() {
            public ClientProxy create(String id) {
                String name = String.valueOf(id);
                IAtomicLong atomicLong = client.getAtomicLong(IdGeneratorService.ATOMIC_LONG_NAME + name);
                return new ClientIdGeneratorProxy(IdGeneratorService.SERVICE_NAME, name, atomicLong);
            }
        });

        register(CountDownLatchService.SERVICE_NAME, new ClientProxyFactory() {
            public ClientProxy create(String id) {
                return new ClientCountDownLatchProxy(CountDownLatchService.SERVICE_NAME, String.valueOf(id));
            }
        });


        for (ProxyFactoryConfig proxyFactoryConfig:config.getProxyFactoryConfigs()){
            try {
                ClientProxyFactory clientProxyFactory = ClassLoaderUtil.newInstance(config.getClassLoader(), proxyFactoryConfig.getClassName());
                register(proxyFactoryConfig.getService(),clientProxyFactory);
            } catch (Exception e) {
                logger.severe(e);
            }
        }
    }

    public void register(String serviceName, ClientProxyFactory factory) {
        if (proxyFactories.putIfAbsent(serviceName, factory) != null) {
            throw new IllegalArgumentException("Factory for service: " + serviceName + " is already registered!");
        }
    }

    public ClientProxy getProxy(String service, String id) {
        final ObjectNamespace ns = new DefaultObjectNamespace(service, id);
        final ClientProxy proxy = proxies.get(ns);
        if (proxy != null) {
            return proxy;
        }
        final ClientProxyFactory factory = proxyFactories.get(service);
        if (factory == null) {
            throw new IllegalArgumentException("No factory registered for service: " + service);
        }
        final ClientProxy clientProxy = factory.create(id);
        initialize(clientProxy);
        final ClientProxy current = proxies.putIfAbsent(ns, clientProxy);
        if (current != null){
            return current;
        }
        triggerListeners(clientProxy, false);
        return clientProxy;
    }

    public ClientProxy removeProxy(String service, String id) {
        final ObjectNamespace ns = new DefaultObjectNamespace(service, id);
        final ClientProxy clientProxy = proxies.remove(ns);
        if (clientProxy != null){
            triggerListeners(clientProxy, true);
        }
        return clientProxy;
    }

    private void initialize(ClientProxy clientProxy) {
        clientProxy.setContext(new ClientContext(client.getSerializationService(), client.getClientClusterService(),
                client.getClientPartitionService(), client.getInvocationService(), client.getClientExecutionService(), this, client.getClientConfig()));
    }

    public Collection<? extends DistributedObject> getDistributedObjects(){
        return Collections.unmodifiableCollection(proxies.values());
    }

    public void destroy() {
        proxies.clear();
        listeners.clear();
    }

    private void triggerListeners(final ClientProxy proxy, final boolean removed) {
        client.getClientExecutionService().execute(new Runnable() {
            public void run() {
                final DistributedObjectEvent event;
                if (removed) {
                    event = new DistributedObjectEvent(DistributedObjectEvent.EventType.DESTROYED, proxy.getServiceName(), proxy);
                } else {
                    event = new DistributedObjectEvent(DistributedObjectEvent.EventType.CREATED, proxy.getServiceName(), proxy);
                }
                for (DistributedObjectListener listener : listeners.values()) {
                    if (removed) {
                        listener.distributedObjectDestroyed(event);
                    } else {
                        listener.distributedObjectCreated(event);
                    }
                }
            }
        });


    }

    public String addDistributedObjectListener(DistributedObjectListener listener) {
        String id = UUID.randomUUID().toString();
        listeners.put(id, listener);
        return id;
    }

    public boolean removeDistributedObjectListener(String id) {
        return listeners.remove(id) != null;
    }
}