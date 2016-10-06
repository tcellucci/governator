/*
 * Copyright 2013 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.governator.guice;

import static com.netflix.governator.internal.BinaryConstant.I13_8192;
import static com.netflix.governator.internal.BinaryConstant.I8_256;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Sets;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.Stage;
import com.netflix.governator.configuration.ConfigurationDocumentation;
import com.netflix.governator.configuration.ConfigurationMapper;
import com.netflix.governator.configuration.ConfigurationProvider;
import com.netflix.governator.guice.lazy.FineGrainedLazySingleton;
import com.netflix.governator.guice.lazy.FineGrainedLazySingletonScope;
import com.netflix.governator.guice.lazy.LazySingleton;
import com.netflix.governator.guice.lazy.LazySingletonScope;
import com.netflix.governator.lifecycle.ClasspathScanner;
import com.netflix.governator.lifecycle.LifecycleConfigurationProviders;
import com.netflix.governator.lifecycle.LifecycleManager;
import com.netflix.governator.lifecycle.LifecycleManagerArguments;
import com.netflix.governator.lifecycle.LifecycleMetadata;
import com.netflix.governator.lifecycle.LifecycleMethods;

class InternalBootstrapModule extends AbstractModule
{
    private BootstrapBinder bootstrapBinder;
    private ClasspathScanner scanner;
    private Stage stage;
    private LifecycleInjectorMode mode;
    private ModuleListBuilder modules;
    private Collection<PostInjectorAction> actions;
    private Collection<ModuleTransformer> transformers;
    private boolean disableAutoBinding;
    private final Collection<BootstrapModule> bootstrapModules;
    
    private static class LifecycleConfigurationProvidersProvider implements Provider<LifecycleConfigurationProviders>
    {
        @Inject(optional = true)
        private Set<ConfigurationProvider> configurationProviders = Sets.newHashSet();

        @Override
        public LifecycleConfigurationProviders get()
        {
            return new LifecycleConfigurationProviders(configurationProviders);
        }
    }
    
    private static class CachedLifecycleMetadataFunction implements Function<Class<?>, LifecycleMethods>{
        private final LoadingCache<Class<?>, LifecycleMethods> cache = CacheBuilder
                .newBuilder()
                .initialCapacity(I13_8192) // number of classes with metadata
                .concurrencyLevel(I8_256)  // number of concurrent metadata producers (no locks for read)
                .softValues()
                .build(new CacheLoader<Class<?>, LifecycleMethods>() {
                    @Override
                    public LifecycleMethods load(Class<?> key) throws Exception {
                        return new LifecycleMethods(key);
                    }
                });

        public LifecycleMethods apply(Class<?>  clz) {
            try {
                return cache.get(clz);
            } catch (ExecutionException e) {
                // caching problem
                throw new RuntimeException(e);
            } 
        };
   }

    public InternalBootstrapModule(Collection<BootstrapModule> bootstrapModules, ClasspathScanner scanner, Stage stage, LifecycleInjectorMode mode, ModuleListBuilder modules, Collection<PostInjectorAction> actions, Collection<ModuleTransformer> transformers, boolean disableAutoBinding) {
        this.scanner = scanner;
        this.stage = stage;
        this.mode = mode;
        this.modules = modules;
        this.actions = actions;
        this.transformers = transformers;
        this.bootstrapModules = bootstrapModules;
        this.disableAutoBinding = disableAutoBinding;
    }

    BootstrapBinder getBootstrapBinder()
    {
        return bootstrapBinder;
    }

    @Override
    protected void configure()
    {
        bind(ConfigurationDocumentation.class).in(Scopes.SINGLETON);
        
        bindScope(LazySingleton.class, LazySingletonScope.get());
        bindScope(FineGrainedLazySingleton.class, FineGrainedLazySingletonScope.get());

        bootstrapBinder = new BootstrapBinder(binder(), stage, mode, modules, actions, transformers, disableAutoBinding);
        
        if ( bootstrapModules != null )
        {
            for (BootstrapModule bootstrapModule : bootstrapModules) {
                bootstrapModule.configure(bootstrapBinder);
            }
        }

        bind(com.netflix.governator.LifecycleManager.class).in(Scopes.SINGLETON);
        bind(new com.google.inject.TypeLiteral<Function<Class<?>, LifecycleMethods>>(){}).toInstance(new CachedLifecycleMetadataFunction());
        binder().bind(LifecycleManagerArguments.class).in(Scopes.SINGLETON);
        binder().bind(LifecycleManager.class).asEagerSingleton();
        binder().bind(LifecycleConfigurationProviders.class).toProvider(LifecycleConfigurationProvidersProvider.class).asEagerSingleton();
        
        this.stage = bootstrapBinder.getStage();
        this.mode = bootstrapBinder.getMode();
    }

    Stage getStage() {
        return stage;
    }
    
    LifecycleInjectorMode getMode() {
        return mode;
    }
    
    boolean isDisableAutoBinding() {
        return disableAutoBinding;
    }
    
    ModuleListBuilder getModuleListBuilder() {
        return modules;
    }
    
    @Provides
    @Singleton
    public ClasspathScanner getClasspathScanner()
    {
        return scanner;
    }
}
