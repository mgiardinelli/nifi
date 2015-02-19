/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.controller.service;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.nifi.annotation.lifecycle.OnAdded;
import org.apache.nifi.annotation.lifecycle.OnRemoved;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.ConfiguredComponent;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.controller.ProcessScheduler;
import org.apache.nifi.controller.ProcessorNode;
import org.apache.nifi.controller.ReportingTaskNode;
import org.apache.nifi.controller.ScheduledState;
import org.apache.nifi.controller.ValidationContextFactory;
import org.apache.nifi.controller.exception.ControllerServiceNotFoundException;
import org.apache.nifi.controller.exception.ProcessorLifeCycleException;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.nar.ExtensionManager;
import org.apache.nifi.nar.NarCloseable;
import org.apache.nifi.processor.SimpleProcessLogger;
import org.apache.nifi.processor.StandardValidationContextFactory;
import org.apache.nifi.util.ObjectHolder;
import org.apache.nifi.util.ReflectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class StandardControllerServiceProvider implements ControllerServiceProvider {

    private static final Logger logger = LoggerFactory.getLogger(StandardControllerServiceProvider.class);

    private final ProcessScheduler processScheduler;
    private final ConcurrentMap<String, ControllerServiceNode> controllerServices;
    private static final Set<Method> validDisabledMethods;

    static {
        // methods that are okay to be called when the service is disabled.
        final Set<Method> validMethods = new HashSet<>();
        for (final Method method : ControllerService.class.getMethods()) {
            validMethods.add(method);
        }
        for (final Method method : Object.class.getMethods()) {
            validMethods.add(method);
        }
        validDisabledMethods = Collections.unmodifiableSet(validMethods);
    }

    public StandardControllerServiceProvider(final ProcessScheduler scheduler) {
        // the following 2 maps must be updated atomically, but we do not lock around them because they are modified
        // only in the createControllerService method, and both are modified before the method returns
        this.controllerServices = new ConcurrentHashMap<>();
        this.processScheduler = scheduler;
    }

    private Class<?>[] getInterfaces(final Class<?> cls) {
        final List<Class<?>> allIfcs = new ArrayList<>();
        populateInterfaces(cls, allIfcs);
        return allIfcs.toArray(new Class<?>[allIfcs.size()]);
    }

    private void populateInterfaces(final Class<?> cls, final List<Class<?>> interfacesDefinedThusFar) {
        final Class<?>[] ifc = cls.getInterfaces();
        if (ifc != null && ifc.length > 0) {
            for (final Class<?> i : ifc) {
                interfacesDefinedThusFar.add(i);
            }
        }

        final Class<?> superClass = cls.getSuperclass();
        if (superClass != null) {
            populateInterfaces(superClass, interfacesDefinedThusFar);
        }
    }
    
    @Override
    public ControllerServiceNode createControllerService(final String type, final String id, final boolean firstTimeAdded) {
        if (type == null || id == null) {
            throw new NullPointerException();
        }
        
        final ClassLoader currentContextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final ClassLoader cl = ExtensionManager.getClassLoader(type);
            final Class<?> rawClass;
            if ( cl == null ) {
                rawClass = Class.forName(type);
            } else {
                Thread.currentThread().setContextClassLoader(cl);
                rawClass = Class.forName(type, false, cl);
            }
            
            final Class<? extends ControllerService> controllerServiceClass = rawClass.asSubclass(ControllerService.class);

            final ControllerService originalService = controllerServiceClass.newInstance();
            final ObjectHolder<ControllerServiceNode> serviceNodeHolder = new ObjectHolder<>(null);
            final InvocationHandler invocationHandler = new InvocationHandler() {
                @Override
                public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
                    final ControllerServiceNode node = serviceNodeHolder.get();
                    final ControllerServiceState state = node.getState();
                    final boolean disabled = (state != ControllerServiceState.ENABLED); // only allow method call if service state is ENABLED.
                    if (disabled && !validDisabledMethods.contains(method)) {
                        // Use nar class loader here because we are implicitly calling toString() on the original implementation.
                        try (final NarCloseable narCloseable = NarCloseable.withNarLoader()) {
                            throw new IllegalStateException("Cannot invoke method " + method + " on Controller Service " + originalService + " because the Controller Service is disabled");
                        } catch (final Throwable e) {
                            throw new IllegalStateException("Cannot invoke method " + method + " on Controller Service with identifier " + id + " because the Controller Service is disabled");
                        }
                    }

                    try (final NarCloseable narCloseable = NarCloseable.withNarLoader()) {
                        return method.invoke(originalService, args);
                    } catch (final InvocationTargetException e) {
                        // If the ControllerService throws an Exception, it'll be wrapped in an InvocationTargetException. We want
                        // to instead re-throw what the ControllerService threw, so we pull it out of the InvocationTargetException.
                        throw e.getCause();
                    }
                }
            };

            final ControllerService proxiedService;
            if ( cl == null ) {
                proxiedService = (ControllerService) Proxy.newProxyInstance(getClass().getClassLoader(), getInterfaces(controllerServiceClass), invocationHandler);
            } else {
                proxiedService = (ControllerService) Proxy.newProxyInstance(cl, getInterfaces(controllerServiceClass), invocationHandler);
            }
            logger.info("Create Controller Service of type {} with identifier {}", type, id);

            final ComponentLog serviceLogger = new SimpleProcessLogger(id, originalService);
            originalService.initialize(new StandardControllerServiceInitializationContext(id, serviceLogger, this));

            final ValidationContextFactory validationContextFactory = new StandardValidationContextFactory(this);

            final ControllerServiceNode serviceNode = new StandardControllerServiceNode(proxiedService, originalService, id, validationContextFactory, this);
            serviceNodeHolder.set(serviceNode);
            serviceNode.setName(rawClass.getSimpleName());
            
            if ( firstTimeAdded ) {
                try (final NarCloseable x = NarCloseable.withNarLoader()) {
                    ReflectionUtils.invokeMethodsWithAnnotation(OnAdded.class, originalService);
                } catch (final Exception e) {
                    throw new ProcessorLifeCycleException("Failed to invoke On-Added Lifecycle methods of " + originalService, e);
                }
            }

            this.controllerServices.put(id, serviceNode);
            return serviceNode;
        } catch (final Throwable t) {
            throw new ControllerServiceNotFoundException(t);
        } finally {
            if (currentContextClassLoader != null) {
                Thread.currentThread().setContextClassLoader(currentContextClassLoader);
            }
        }
    }
    
    
    
    @Override
    public void disableReferencingServices(final ControllerServiceNode serviceNode) {
        // Get a list of all Controller Services that need to be disabled, in the order that they need to be
        // disabled.
        final List<ControllerServiceNode> toDisable = findRecursiveReferences(serviceNode, ControllerServiceNode.class);
        final Set<ControllerServiceNode> serviceSet = new HashSet<>(toDisable);
        
        for ( final ControllerServiceNode nodeToDisable : toDisable ) {
            final ControllerServiceState state = nodeToDisable.getState();
            
            if ( state != ControllerServiceState.DISABLED && state != ControllerServiceState.DISABLING ) {
                nodeToDisable.verifyCanDisable(serviceSet);
            }
        }
        
        Collections.reverse(toDisable);
        for ( final ControllerServiceNode nodeToDisable : toDisable ) {
            final ControllerServiceState state = nodeToDisable.getState();
            
            if ( state != ControllerServiceState.DISABLED && state != ControllerServiceState.DISABLING ) {
                disableControllerService(nodeToDisable);
            }
        }
    }
    
    
    @Override
    public void scheduleReferencingComponents(final ControllerServiceNode serviceNode) {
        // find all of the schedulable components (processors, reporting tasks) that refer to this Controller Service,
        // or a service that references this controller service, etc.
        final List<ProcessorNode> processors = findRecursiveReferences(serviceNode, ProcessorNode.class);
        final List<ReportingTaskNode> reportingTasks = findRecursiveReferences(serviceNode, ReportingTaskNode.class);
        
        // verify that  we can start all components (that are not disabled) before doing anything
        for ( final ProcessorNode node : processors ) {
            if ( node.getScheduledState() != ScheduledState.DISABLED ) {
                node.verifyCanStart();
            }
        }
        for ( final ReportingTaskNode node : reportingTasks ) {
            if ( node.getScheduledState() != ScheduledState.DISABLED ) {
                node.verifyCanStart();
            }
        }
        
        // start all of the components that are not disabled
        for ( final ProcessorNode node : processors ) {
            if ( node.getScheduledState() != ScheduledState.DISABLED ) {
                node.getProcessGroup().startProcessor(node);
            }
        }
        for ( final ReportingTaskNode node : reportingTasks ) {
            if ( node.getScheduledState() != ScheduledState.DISABLED ) {
                processScheduler.schedule(node);
            }
        }
    }
    
    @Override
    public void unscheduleReferencingComponents(final ControllerServiceNode serviceNode) {
        // find all of the schedulable components (processors, reporting tasks) that refer to this Controller Service,
        // or a service that references this controller service, etc.
        final List<ProcessorNode> processors = findRecursiveReferences(serviceNode, ProcessorNode.class);
        final List<ReportingTaskNode> reportingTasks = findRecursiveReferences(serviceNode, ReportingTaskNode.class);
        
        // verify that  we can stop all components (that are running) before doing anything
        for ( final ProcessorNode node : processors ) {
            if ( node.getScheduledState() == ScheduledState.RUNNING ) {
                node.verifyCanStop();
            }
        }
        for ( final ReportingTaskNode node : reportingTasks ) {
            if ( node.getScheduledState() == ScheduledState.RUNNING ) {
                node.verifyCanStop();
            }
        }
        
        // stop all of the components that are running
        for ( final ProcessorNode node : processors ) {
            if ( node.getScheduledState() == ScheduledState.RUNNING ) {
                node.getProcessGroup().stopProcessor(node);
            }
        }
        for ( final ReportingTaskNode node : reportingTasks ) {
            if ( node.getScheduledState() == ScheduledState.RUNNING ) {
                processScheduler.unschedule(node);
            }
        }
    }
    
    @Override
    public void enableControllerService(final ControllerServiceNode serviceNode) {
        serviceNode.verifyCanEnable();
        processScheduler.enableControllerService(serviceNode);
    }
    
    @Override
    public void disableControllerService(final ControllerServiceNode serviceNode) {
        serviceNode.verifyCanDisable();
        processScheduler.disableControllerService(serviceNode);
    }

    @Override
    public ControllerService getControllerService(final String serviceIdentifier) {
        final ControllerServiceNode node = controllerServices.get(serviceIdentifier);
        return (node == null) ? null : node.getProxiedControllerService();
    }

    @Override
    public boolean isControllerServiceEnabled(final ControllerService service) {
        return isControllerServiceEnabled(service.getIdentifier());
    }

    @Override
    public boolean isControllerServiceEnabled(final String serviceIdentifier) {
        final ControllerServiceNode node = controllerServices.get(serviceIdentifier);
        return (node == null) ? false : (ControllerServiceState.ENABLED == node.getState());
    }

    @Override
    public ControllerServiceNode getControllerServiceNode(final String serviceIdentifier) {
        return controllerServices.get(serviceIdentifier);
    }

    @Override
    public Set<String> getControllerServiceIdentifiers(final Class<? extends ControllerService> serviceType) {
        final Set<String> identifiers = new HashSet<>();
        for (final Map.Entry<String, ControllerServiceNode> entry : controllerServices.entrySet()) {
            if (requireNonNull(serviceType).isAssignableFrom(entry.getValue().getProxiedControllerService().getClass())) {
                identifiers.add(entry.getKey());
            }
        }

        return identifiers;
    }
    
    @Override
    public String getControllerServiceName(final String serviceIdentifier) {
    	final ControllerServiceNode node = getControllerServiceNode(serviceIdentifier);
    	return node == null ? null : node.getName();
    }
    
    public void removeControllerService(final ControllerServiceNode serviceNode) {
        final ControllerServiceNode existing = controllerServices.get(serviceNode.getIdentifier());
        if ( existing == null || existing != serviceNode ) {
            throw new IllegalStateException("Controller Service " + serviceNode + " does not exist in this Flow");
        }
        
        serviceNode.verifyCanDelete();
        
        try (final NarCloseable x = NarCloseable.withNarLoader()) {
            final ConfigurationContext configurationContext = new StandardConfigurationContext(serviceNode, this);
            ReflectionUtils.quietlyInvokeMethodsWithAnnotation(OnRemoved.class, serviceNode.getControllerServiceImplementation(), configurationContext);
        }
        
        for ( final Map.Entry<PropertyDescriptor, String> entry : serviceNode.getProperties().entrySet() ) {
            final PropertyDescriptor descriptor = entry.getKey();
            if (descriptor.getControllerServiceDefinition() != null ) {
                final String value = entry.getValue() == null ? descriptor.getDefaultValue() : entry.getValue();
                if ( value != null ) {
                    final ControllerServiceNode referencedNode = getControllerServiceNode(value);
                    if ( referencedNode != null ) {
                        referencedNode.removeReference(serviceNode);
                    }
                }
            }
        }
        
        controllerServices.remove(serviceNode.getIdentifier());
    }
    
    @Override
    public Set<ControllerServiceNode> getAllControllerServices() {
    	return new HashSet<>(controllerServices.values());
    }
    
    
    /**
     * Returns a List of all components that reference the given referencedNode (either directly or indirectly through
     * another service) that are also of the given componentType. The list that is returned is in the order in which they will
     * need to be 'activated' (enabled/started).
     * @param referencedNode
     * @param componentType
     * @return
     */
    private <T> List<T> findRecursiveReferences(final ControllerServiceNode referencedNode, final Class<T> componentType) {
        final List<T> references = new ArrayList<>();
        
        for ( final ConfiguredComponent referencingComponent : referencedNode.getReferences().getReferencingComponents() ) {
            if ( componentType.isAssignableFrom(referencingComponent.getClass()) ) {
                references.add(componentType.cast(referencingComponent));
            }
            
            if ( referencingComponent instanceof ControllerServiceNode ) {
                final ControllerServiceNode referencingNode = (ControllerServiceNode) referencingComponent;
                
                // find components recursively that depend on referencingNode.
                final List<T> recursive = findRecursiveReferences(referencingNode, componentType);
                
                // For anything that depends on referencing node, we want to add it to the list, but we know
                // that it must come after the referencing node, so we first remove any existing occurrence.
                references.removeAll(recursive);
                references.addAll(recursive);
            }
        }
        
        return references;
    }
    
    
    @Override
    public void enableReferencingServices(final ControllerServiceNode serviceNode) {
        final List<ControllerServiceNode> recursiveReferences = findRecursiveReferences(serviceNode, ControllerServiceNode.class);
        enableReferencingServices(serviceNode, recursiveReferences);
    }
    
    private void enableReferencingServices(final ControllerServiceNode serviceNode, final List<ControllerServiceNode> recursiveReferences) {
        if ( serviceNode.getState() != ControllerServiceState.ENABLED && serviceNode.getState() != ControllerServiceState.ENABLING ) {
            serviceNode.verifyCanEnable(new HashSet<>(recursiveReferences));
        }
        
        final Set<ControllerServiceNode> ifEnabled = new HashSet<>();
        final List<ControllerServiceNode> toEnable = findRecursiveReferences(serviceNode, ControllerServiceNode.class);
        for ( final ControllerServiceNode nodeToEnable : toEnable ) {
            final ControllerServiceState state = nodeToEnable.getState();
            if ( state != ControllerServiceState.ENABLED && state != ControllerServiceState.ENABLING ) {
                nodeToEnable.verifyCanEnable(ifEnabled);
                ifEnabled.add(nodeToEnable);
            }
        }
        
        for ( final ControllerServiceNode nodeToEnable : toEnable ) {
            final ControllerServiceState state = nodeToEnable.getState();
            if ( state != ControllerServiceState.ENABLED && state != ControllerServiceState.ENABLING ) {
                enableControllerService(nodeToEnable);
            }
        }
    }
    
    @Override
    public void verifyCanEnableReferencingServices(final ControllerServiceNode serviceNode) {
        final List<ControllerServiceNode> referencingServices = findRecursiveReferences(serviceNode, ControllerServiceNode.class);
        final Set<ControllerServiceNode> referencingServiceSet = new HashSet<>(referencingServices);
        
        for ( final ControllerServiceNode referencingService : referencingServices ) {
            referencingService.verifyCanEnable(referencingServiceSet);
        }
    }
    
    @Override
    public void verifyCanScheduleReferencingComponents(final ControllerServiceNode serviceNode) {
        final List<ControllerServiceNode> referencingServices = findRecursiveReferences(serviceNode, ControllerServiceNode.class);
        final List<ReportingTaskNode> referencingReportingTasks = findRecursiveReferences(serviceNode, ReportingTaskNode.class);
        final List<ProcessorNode> referencingProcessors = findRecursiveReferences(serviceNode, ProcessorNode.class);
        
        final Set<ControllerServiceNode> referencingServiceSet = new HashSet<>(referencingServices);
        
        for ( final ReportingTaskNode taskNode : referencingReportingTasks ) {
            if ( taskNode.getScheduledState() != ScheduledState.DISABLED ) {
                taskNode.verifyCanStart(referencingServiceSet);
            }
        }
        
        for ( final ProcessorNode procNode : referencingProcessors ) {
            if ( procNode.getScheduledState() != ScheduledState.DISABLED ) {
                procNode.verifyCanStart(referencingServiceSet);
            }
        }
    }
    
}
