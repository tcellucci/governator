/*
 * Copyright 2012 Netflix, Inc.
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

package com.netflix.governator.lifecycle;

import static com.netflix.governator.internal.BinaryConstant.I15_32768;
import static com.netflix.governator.internal.BinaryConstant.I2_4;
import static com.netflix.governator.internal.BinaryConstant.I3_8;
import static com.netflix.governator.internal.BinaryConstant.I4_16;
import static com.netflix.governator.internal.BinaryConstant.I5_32;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.annotation.Resources;
import javax.validation.Constraint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.netflix.governator.annotations.Configuration;
import com.netflix.governator.annotations.ConfigurationVariable;
import com.netflix.governator.annotations.PreConfiguration;

/**
 * Used internally (i.e., INTERNALLY) to hold the class metadata important to the LifecycleManager
 */
public class LifecycleMetadata {
    private static final Field[] EMPTY_FIELDS = new Field[0];
    private static final Method[] EMPTY_METHODS = new Method[0];
    
    static class LifecycleMetadataBuilder {
        private static final Logger log = LoggerFactory.getLogger(LifecycleMetadataBuilder.class);
        private static final Collection<Class<? extends Annotation>> fieldAnnotations;
        private static final Collection<Class<? extends Annotation>> methodAnnotations;
        private static final Collection<Class<? extends Annotation>> classAnnotations;

        static {
            ImmutableSet.Builder<Class<? extends Annotation>> methodAnnotationsBuilder = ImmutableSet.builder();
            methodAnnotationsBuilder.add(PreConfiguration.class);
            methodAnnotationsBuilder.add(PostConstruct.class);
            methodAnnotationsBuilder.add(PreDestroy.class);
            methodAnnotationsBuilder.add(Resource.class);
            methodAnnotationsBuilder.add(Resources.class);
            methodAnnotationsBuilder.add(com.netflix.governator.annotations.WarmUp.class);
            methodAnnotations = methodAnnotationsBuilder.build();

            ImmutableSet.Builder<Class<? extends Annotation>> fieldAnnotationsBuilder = ImmutableSet.builder();
            fieldAnnotationsBuilder.add(Configuration.class);
            fieldAnnotationsBuilder.add(Resource.class);
            fieldAnnotationsBuilder.add(Resources.class);
            fieldAnnotationsBuilder.add(ConfigurationVariable.class);
            fieldAnnotations = fieldAnnotationsBuilder.build();

            ImmutableSet.Builder<Class<? extends Annotation>> classAnnotationsBuilder = ImmutableSet.builder();
            classAnnotationsBuilder.add(Resource.class);
            classAnnotationsBuilder.add(Resources.class);
            classAnnotations = classAnnotationsBuilder.build();
        }

        private boolean hasValidations = false;
        private boolean hasResources;
        private final Multimap<Class<? extends Annotation>, Field> fieldMap = ArrayListMultimap.create(I3_8, I5_32);
        private final Multimap<Class<? extends Annotation>, Method> methodMap = ArrayListMultimap.create(I4_16, I5_32);
        private final Multimap<Class<? extends Annotation>, Annotation> classMap = ArrayListMultimap.create(I2_4, I3_8);
        
        LifecycleMetadataBuilder(Class<?> clazz) {
            addLifeCycleMethods(clazz, ArrayListMultimap.<Class<? extends Annotation>, String> create());
            this.hasResources = fieldMap.containsKey(Resource.class) || 
                    fieldMap.containsKey(Resources.class) ||
                    methodMap.containsKey(Resource.class) ||
                    methodMap.containsKey(Resources.class) ||
                    classMap.containsKey(Resource.class) ||
                    classMap.containsKey(Resources.class);
            this.hasValidations = this.hasValidations ||  !methodMap.isEmpty() || !fieldMap.isEmpty();            
        }
        
        void addLifeCycleMethods(Class<?> clazz, Multimap<Class<? extends Annotation>, String> usedNames) {
            if (clazz == null) {
                return;
            }

            for (Class<? extends Annotation> annotationClass : classAnnotations) {
                if (clazz.isAnnotationPresent(annotationClass)) {
                    classMap.put(annotationClass, clazz.getAnnotation(annotationClass));
                }                
            }

            for (Field field : getDeclaredFields(clazz)) {
                if (field.isSynthetic()) {
                    continue;
                }

                if (!hasValidations) {
                    checkForValidations(field);
                }

                for (Class<? extends Annotation> annotationClass : fieldAnnotations) {
                    processField(field, annotationClass, usedNames);
                }
            }

            for (Method method : getDeclaredMethods(clazz)) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }

                for (Class<? extends Annotation> annotationClass : methodAnnotations) {
                    processMethod(method, annotationClass, usedNames);
                }
            }

            addLifeCycleMethods(clazz.getSuperclass(), usedNames);
            for (Class<?> face : clazz.getInterfaces()) {
                addLifeCycleMethods(face, usedNames);
            }
            
         }

        private Method[] getDeclaredMethods(Class<?> clazz) {
            try {
                return clazz.getDeclaredMethods();
            } catch (Throwable e) {
                handleReflectionError(clazz, e);
            }

            return EMPTY_METHODS;
        }

        private Field[] getDeclaredFields(Class<?> clazz) {
            try {
                return clazz.getDeclaredFields();
            } catch (Throwable e) {
                handleReflectionError(clazz, e);
            }

            return EMPTY_FIELDS;
        }

        private void handleReflectionError(Class<?> clazz, Throwable e) {
            if (e != null) {
                if ((e instanceof NoClassDefFoundError) || (e instanceof ClassNotFoundException)) {
                    log.debug(String.format(
                            "Class %s could not be resolved because of a class path error. Governator cannot further process the class.",
                            clazz.getName()), e);
                    return;
                }

                handleReflectionError(clazz, e.getCause());
            }
        }

        private void checkForValidations(Field field) {
            this.hasValidations =field.getAnnotationsByType(Constraint.class).length > 0;
        }

        private void processField(Field field, Class<? extends Annotation> annotationClass,
                Multimap<Class<? extends Annotation>, String> usedNames) {
            if (field.isAnnotationPresent(annotationClass)) {
                String fieldName = field.getName();
                if (!usedNames.get(annotationClass).contains(fieldName)) {
                    field.setAccessible(true);
                    usedNames.put(annotationClass, fieldName);
                    fieldMap.put(annotationClass, field);
                    try {
                        fieldHandlesMap.put(field, new MethodHandle[] { 
                                METHOD_HANDLE_LOOKUP.unreflectGetter(field),
                                METHOD_HANDLE_LOOKUP.unreflectSetter(field) 
                                });
                    } catch (IllegalAccessException e) {
                        // that's ok, will use reflected method
                    }                
                }
            }
        }

        private void processMethod(Method method, Class<? extends Annotation> annotationClass,
                Multimap<Class<? extends Annotation>, String> usedNames) {
            if (method.isAnnotationPresent(annotationClass)) {
                String methodName = method.getName();
                if (!usedNames.get(annotationClass).contains(methodName)) {
                    method.setAccessible(true);
                    usedNames.put(annotationClass, methodName);
                    methodMap.put(annotationClass, method);
                    methodHandlesMap.computeIfAbsent(method, m->{
                        try {
                            return METHOD_HANDLE_LOOKUP.unreflect(m);                                   
                        } catch (IllegalAccessException e) {
                            // that's ok, will use reflected method
                            return null;
                        }
                    });
                }
            }
        }        
        
        LifecycleMetadata build() {
            Map<Class<? extends Annotation>, Method[]> lifecycleMetadataMethodMap = new HashMap<>();
            for (Map.Entry<Class<? extends Annotation>, Collection<Method>> entry : this.methodMap.asMap().entrySet()) {
                lifecycleMetadataMethodMap.put(entry.getKey(), entry.getValue().toArray(EMPTY_METHODS));
            }
            Map<Class<? extends Annotation>, Field[]> lifecycleMetadataFieldMap = new HashMap<>();
            for (Map.Entry<Class<? extends Annotation>, Collection<Field>> entry : this.fieldMap.asMap().entrySet()) {
                lifecycleMetadataFieldMap.put(entry.getKey(), entry.getValue().toArray(EMPTY_FIELDS));
            }
            Map<Class<? extends Annotation>, Annotation[]>  lifecycleMetadataClassMap = new HashMap<>();
            for (Class<? extends Annotation> annotationClass : LifecycleMetadataBuilder.classAnnotations) {            
                Annotation[] annotationsArray = (Annotation[])Array.newInstance(annotationClass, 0);
                Collection<Annotation> annotations = classMap.get(annotationClass);
                if (annotations != null) {
                    annotationsArray = annotations.toArray(annotationsArray);
                }            
                lifecycleMetadataClassMap.put(annotationClass, annotationsArray);
            }       
            return new LifecycleMetadata(lifecycleMetadataClassMap, lifecycleMetadataMethodMap, lifecycleMetadataFieldMap, hasValidations, hasResources);
        }
    }

    private final boolean hasValidations;
    private final boolean hasResources;
    private final Map<Class<? extends Annotation>, Method[]> methodMap;
    private final Map<Class<? extends Annotation>, Field[]> fieldMap;
    private final Map<Class<? extends Annotation>, Annotation[]> classMap;

    @Deprecated
    protected LifecycleMetadata(Class<?> clazz) {
        LifecycleMetadataBuilder builder = new LifecycleMetadataBuilder(clazz);
        Map<Class<? extends Annotation>, Method[]> lifecycleMetadataMethodMap = new HashMap<>();
        for (Map.Entry<Class<? extends Annotation>, Collection<Method>> entry : builder.methodMap.asMap().entrySet()) {
            lifecycleMetadataMethodMap.put(entry.getKey(), entry.getValue().toArray(EMPTY_METHODS));
        }
        Map<Class<? extends Annotation>, Field[]> lifecycleMetadataFieldMap = new HashMap<>();
        for (Map.Entry<Class<? extends Annotation>, Collection<Field>> entry : builder.fieldMap.asMap().entrySet()) {
            lifecycleMetadataFieldMap.put(entry.getKey(), entry.getValue().toArray(EMPTY_FIELDS));
        }
        Map<Class<? extends Annotation>, Annotation[]>  lifecycleMetadataClassMap = new HashMap<>();
        for (Class<? extends Annotation> annotationClass : LifecycleMetadataBuilder.classAnnotations) {            
            Annotation[] annotationsArray = (Annotation[])Array.newInstance(annotationClass, 0);
            Collection<Annotation> annotations = builder.classMap.get(annotationClass);
            if (annotations != null) {
                annotationsArray = annotations.toArray(annotationsArray);
            }            
            lifecycleMetadataClassMap.put(annotationClass, annotationsArray);
        }       
        this.classMap = lifecycleMetadataClassMap;
        this.methodMap = lifecycleMetadataMethodMap;
        this.fieldMap = lifecycleMetadataFieldMap;
        this.hasValidations = builder.hasValidations;
        this.hasResources = builder.hasResources;        
    }
    
    private LifecycleMetadata(Map<Class<? extends Annotation>, Annotation[]> classMap, Map<Class<? extends Annotation>, Method[]> methodMap, Map<Class<? extends Annotation>, Field[]> fieldMap, boolean hasValidations, boolean hasResources) {
        this.classMap = classMap;
        this.methodMap = methodMap;
        this.fieldMap = fieldMap;
        this.hasValidations = hasValidations;
        this.hasResources = hasResources;
    }

    public boolean hasLifecycleAnnotations() {
        return hasValidations;
    }

    public boolean hasResources() {
        return hasResources;
    }

    public Method[] annotatedMethods(Class<? extends Annotation> annotation) {
        Method[] methods = methodMap.get(annotation);
        return (methods != null) ? methods : EMPTY_METHODS;
    }

    public Field[] annotatedFields(Class<? extends Annotation> annotation) {
        Field[] fields = fieldMap.get(annotation);
        return (fields != null) ? fields : EMPTY_FIELDS;
    }

    @SuppressWarnings("unchecked")
    public <T extends Annotation> T[] classAnnotations(Class<T> annotation) {
        Annotation[] annotations = classMap.get(annotation);
        return (annotations != null) ? (T[])annotations : (T[])Array.newInstance(annotation, 0);
    }
    
    public void methodInvoke(Class<? extends Annotation> annotation, Object obj) throws Exception {
        if  (methodMap.containsKey(annotation)) {
           for (Method m : methodMap.get(annotation)) {
               methodInvoke(m, obj);
           }
        }
    }
    
    final static Lookup METHOD_HANDLE_LOOKUP = MethodHandles.lookup();
    final static Map<Method, MethodHandle> methodHandlesMap = new ConcurrentHashMap<>(I15_32768);
    final static Map<Field, MethodHandle[]> fieldHandlesMap = new ConcurrentHashMap<>(I15_32768);
    final static MethodHandle[] EMPTY_FIELD_HANDLES = new MethodHandle[0];
    
    public static void methodInvoke(Method method, Object target) throws InvocationTargetException, IllegalAccessException {
        MethodHandle handler = methodHandlesMap.get(method);
        if (handler != null) {
            try {
                handler.invoke(target);
            } catch (Throwable e) {
                throw new InvocationTargetException(e, "invokedynamic");
            }
        }
        else {
            // fall through to reflected invocation
            method.invoke(target);
        }
    }
    
    public static <T> T fieldGet(Field field, Object obj) throws InvocationTargetException, IllegalAccessException {
        MethodHandle[] fieldHandler = fieldHandlesMap.computeIfAbsent(field, LifecycleMetadata::updateFieldHandles);
        if (fieldHandler != EMPTY_FIELD_HANDLES) {
            try {
                return Modifier.isStatic(field.getModifiers()) ? (T)fieldHandler[0].invoke() : (T)fieldHandler[0].bindTo(obj).invoke();
            } catch (Throwable e) {
                throw new InvocationTargetException(e, "invokedynamic: field=" + field + ", object=" + obj);
            }
        }
        else {
            return (T)field.get(obj);
        }   
    }

    public static void fieldSet(Field field, Object object, Object value) throws InvocationTargetException, IllegalAccessException {
        MethodHandle[] fieldHandler = fieldHandlesMap.computeIfAbsent(field, LifecycleMetadata::updateFieldHandles);
        if (fieldHandler != EMPTY_FIELD_HANDLES) {
            try {
                if (Modifier.isStatic(field.getModifiers())) {
                    fieldHandler[1].invoke(value);
                }
                else {
                    fieldHandler[1].bindTo(object).invoke(value);
                }
            } catch (Throwable e) {
                throw new InvocationTargetException(e, "invokedynamic: field=" + field + ", object=" + object);
            }
        }
        else {
            field.set(object, value);
        }
    }

    private static MethodHandle[] updateFieldHandles(Field field) {
        MethodHandle[] handles;
        try {
            handles = new MethodHandle[] { 
                    METHOD_HANDLE_LOOKUP.unreflectGetter(field),
                    METHOD_HANDLE_LOOKUP.unreflectSetter(field) 
                    };
        } catch (IllegalAccessException e) {
            // that's ok, will use reflected method
            handles = EMPTY_FIELD_HANDLES;
        }                                
        return handles;
    }

}
