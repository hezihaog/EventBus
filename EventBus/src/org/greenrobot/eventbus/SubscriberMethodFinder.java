/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
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
package org.greenrobot.eventbus;

import org.greenrobot.eventbus.meta.SubscriberInfo;
import org.greenrobot.eventbus.meta.SubscriberInfoIndex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class SubscriberMethodFinder {
    /*
     * In newer class files, compilers may add methods. Those are called bridge or synthetic methods.
     * EventBus must ignore both. There modifiers are not public but defined in the Java class file format:
     * http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6-200-A.1
     */
    private static final int BRIDGE = 0x40;
    private static final int SYNTHETIC = 0x1000;

    private static final int MODIFIERS_IGNORE = Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;
    private static final Map<Class<?>, List<SubscriberMethod>> METHOD_CACHE = new ConcurrentHashMap<>();

    private List<SubscriberInfoIndex> subscriberInfoIndexes;
    private final boolean strictMethodVerification;
    private final boolean ignoreGeneratedIndex;

    private static final int POOL_SIZE = 4;
    private static final FindState[] FIND_STATE_POOL = new FindState[POOL_SIZE];

    SubscriberMethodFinder(List<SubscriberInfoIndex> subscriberInfoIndexes, boolean strictMethodVerification,
                           boolean ignoreGeneratedIndex) {
        this.subscriberInfoIndexes = subscriberInfoIndexes;
        this.strictMethodVerification = strictMethodVerification;
        this.ignoreGeneratedIndex = ignoreGeneratedIndex;
    }

    /**
     * 查找订阅者的订阅方法，将订阅信息封装在SubscriberMethod类中，多个方法，所以返回值为一个List集合
     *
     * @param subscriberClass 订阅者Class
     */
    List<SubscriberMethod> findSubscriberMethods(Class<?> subscriberClass) {
        //先从缓存中查找，有则直接使用
        List<SubscriberMethod> subscriberMethods = METHOD_CACHE.get(subscriberClass);
        if (subscriberMethods != null) {
            return subscriberMethods;
        }
        //是否忽略apt生成的索引，ignoreGeneratedIndex默认为false
        if (ignoreGeneratedIndex) {
            subscriberMethods = findUsingReflection(subscriberClass);
        } else {
            //反射获取所有订阅的方法
            subscriberMethods = findUsingInfo(subscriberClass);
        }
        //没有任何一个订阅方法，抛出异常
        if (subscriberMethods.isEmpty()) {
            throw new EventBusException("Subscriber " + subscriberClass
                    + " and its super classes have no public methods with the @Subscribe annotation");
        } else {
            //获取到所有订阅方法后，保存到缓存中，然后返回
            METHOD_CACHE.put(subscriberClass, subscriberMethods);
            return subscriberMethods;
        }
    }

    /**
     * 反射查找订阅者所有的订阅方法
     *
     * @param subscriberClass 订阅者Class
     */
    private List<SubscriberMethod> findUsingInfo(Class<?> subscriberClass) {
        //获取当前线程的查找状态
        FindState findState = prepareFindState();
        //初始化一些值
        findState.initForSubscriber(subscriberClass);
        while (findState.clazz != null) {
            //获取订阅信息
            findState.subscriberInfo = getSubscriberInfo(findState);
            //初始化时，findState.subscriberInfo为null
            if (findState.subscriberInfo != null) {
                SubscriberMethod[] array = findState.subscriberInfo.getSubscriberMethods();
                for (SubscriberMethod subscriberMethod : array) {
                    if (findState.checkAdd(subscriberMethod.method, subscriberMethod.eventType)) {
                        findState.subscriberMethods.add(subscriberMethod);
                    }
                }
            } else {
                //反射获取订阅者的所有订阅方法
                findUsingReflectionInSingleClass(findState);
            }
            //配置父类Class信息，会自动忽略系统类来提高性能
            findState.moveToSuperclass();
        }
        //转移findState上保存的List<SubscriberMethod>，并对FindState中间对象回收
        return getMethodsAndRelease(findState);
    }

    /**
     * 将FindState上保存的订阅方法保存到一个List集合，并将FindState回收，将FindState类放到对象池中复用
     *
     * @param findState 保存了订阅信息的中间类
     */
    private List<SubscriberMethod> getMethodsAndRelease(FindState findState) {
        //将订阅的方法信息保存到一个List集合
        List<SubscriberMethod> subscriberMethods = new ArrayList<>(findState.subscriberMethods);
        //回收，就是重置字段
        findState.recycle();
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                //保存到对象池中复用
                if (FIND_STATE_POOL[i] == null) {
                    FIND_STATE_POOL[i] = findState;
                    break;
                }
            }
        }
        //返回这个List集合
        return subscriberMethods;
    }

    /**
     * 获取一个查找状态
     */
    private FindState prepareFindState() {
        synchronized (FIND_STATE_POOL) {
            //享元模式，从对象池中找，避免频繁创建FindState类
            for (int i = 0; i < POOL_SIZE; i++) {
                FindState state = FIND_STATE_POOL[i];
                if (state != null) {
                    FIND_STATE_POOL[i] = null;
                    return state;
                }
            }
        }
        //没有缓存对象可用，则创建一个
        return new FindState();
    }

    /**
     * 获取订阅信息
     *
     * @param findState 查找状态类
     */
    private SubscriberInfo getSubscriberInfo(FindState findState) {
        //初始化时，subscriberInfo和subscriberInfoIndexes都为null，所以初始化时返回null
        if (findState.subscriberInfo != null && findState.subscriberInfo.getSuperSubscriberInfo() != null) {
            SubscriberInfo superclassInfo = findState.subscriberInfo.getSuperSubscriberInfo();
            if (findState.clazz == superclassInfo.getSubscriberClass()) {
                return superclassInfo;
            }
        }
        if (subscriberInfoIndexes != null) {
            for (SubscriberInfoIndex index : subscriberInfoIndexes) {
                SubscriberInfo info = index.getSubscriberInfo(findState.clazz);
                if (info != null) {
                    return info;
                }
            }
        }
        return null;
    }

    private List<SubscriberMethod> findUsingReflection(Class<?> subscriberClass) {
        FindState findState = prepareFindState();
        findState.initForSubscriber(subscriberClass);
        while (findState.clazz != null) {
            findUsingReflectionInSingleClass(findState);
            findState.moveToSuperclass();
        }
        return getMethodsAndRelease(findState);
    }

    /**
     * 反射获取订阅者的所有订阅方法
     *
     * @param findState 查找状态
     */
    private void findUsingReflectionInSingleClass(FindState findState) {
        Method[] methods;
        try {
            //使用getDeclaredMethods，反射获取所有方法，不会获取到父类中的方法，避免查找耗时，尤其是Activity，一般我们都是在子类上使用
            methods = findState.clazz.getDeclaredMethods();
        } catch (Throwable th) {
            // Workaround for java.lang.NoClassDefFoundError, see https://github.com/greenrobot/EventBus/issues/149
            methods = findState.clazz.getMethods();
            findState.skipSuperClasses = true;
        }
        //遍历订阅者的方法
        for (Method method : methods) {
            //获取修饰符
            int modifiers = method.getModifiers();
            //判断方法的修饰符是否为Public公开，并且不是static静态方法，
            if ((modifiers & Modifier.PUBLIC) != 0 && (modifiers & MODIFIERS_IGNORE) == 0) {
                //获取方法参数
                Class<?>[] parameterTypes = method.getParameterTypes();
                //限定订阅方法的方法参数为1个，就是event事件类
                if (parameterTypes.length == 1) {
                    //判断方法是否加了@Subscribe注解，必须加了才处理
                    Subscribe subscribeAnnotation = method.getAnnotation(Subscribe.class);
                    if (subscribeAnnotation != null) {
                        //获取第一个参数，就是事件event
                        Class<?> eventType = parameterTypes[0];
                        //检查是否已经添加过了，没有添加过才继续
                        if (findState.checkAdd(method, eventType)) {
                            //获取@Subscribe注解上的threadMode参数，就是事件回调的线程策略
                            ThreadMode threadMode = subscribeAnnotation.threadMode();
                            //创建SubscriberMethod对象，代表每个订阅方法的信息
                            //将@Subscribe注解上定义的事件类型、线程回调策略、回调优先级、是否粘性等字段保存到SubscriberMethod类中
                            //再将SubscriberMethod对象保存到findState.subscriberMethods订阅的方法列表中
                            findState.subscriberMethods.add(new SubscriberMethod(method, eventType, threadMode,
                                    subscribeAnnotation.priority(), subscribeAnnotation.sticky()));
                        }
                    }
                } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                    String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                    throw new EventBusException("@Subscribe method " + methodName +
                            "must have exactly 1 parameter but has " + parameterTypes.length);
                }
            } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                throw new EventBusException(methodName +
                        " is a illegal @Subscribe method: must be public, non-static, and non-abstract");
            }
        }
    }

    static void clearCaches() {
        METHOD_CACHE.clear();
    }

    /**
     * 查找状态实体类
     */
    static class FindState {
        final List<SubscriberMethod> subscriberMethods = new ArrayList<>();
        final Map<Class, Object> anyMethodByEventType = new HashMap<>();
        final Map<String, Class> subscriberClassByMethodKey = new HashMap<>();
        final StringBuilder methodKeyBuilder = new StringBuilder(128);

        Class<?> subscriberClass;
        Class<?> clazz;
        boolean skipSuperClasses;
        SubscriberInfo subscriberInfo;

        void initForSubscriber(Class<?> subscriberClass) {
            this.subscriberClass = clazz = subscriberClass;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        /**
         * 回收操作，重置字段
         */
        void recycle() {
            subscriberMethods.clear();
            anyMethodByEventType.clear();
            subscriberClassByMethodKey.clear();
            methodKeyBuilder.setLength(0);
            subscriberClass = null;
            clazz = null;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        boolean checkAdd(Method method, Class<?> eventType) {
            // 2 level check: 1st level with event type only (fast), 2nd level with complete signature when required.
            // Usually a subscriber doesn't have methods listening to the same event type.
            Object existing = anyMethodByEventType.put(eventType, method);
            if (existing == null) {
                return true;
            } else {
                if (existing instanceof Method) {
                    if (!checkAddWithMethodSignature((Method) existing, eventType)) {
                        // Paranoia check
                        throw new IllegalStateException();
                    }
                    // Put any non-Method object to "consume" the existing Method
                    anyMethodByEventType.put(eventType, this);
                }
                return checkAddWithMethodSignature(method, eventType);
            }
        }

        private boolean checkAddWithMethodSignature(Method method, Class<?> eventType) {
            methodKeyBuilder.setLength(0);
            methodKeyBuilder.append(method.getName());
            methodKeyBuilder.append('>').append(eventType.getName());

            String methodKey = methodKeyBuilder.toString();
            Class<?> methodClass = method.getDeclaringClass();
            Class<?> methodClassOld = subscriberClassByMethodKey.put(methodKey, methodClass);
            if (methodClassOld == null || methodClassOld.isAssignableFrom(methodClass)) {
                // Only add if not already found in a sub class
                return true;
            } else {
                // Revert the put, old class is further down the class hierarchy
                subscriberClassByMethodKey.put(methodKey, methodClassOld);
                return false;
            }
        }

        /**
         * 配置父类Class信息，会自动忽略系统类来提高性能
         */
        void moveToSuperclass() {
            if (skipSuperClasses) {
                clazz = null;
            } else {
                clazz = clazz.getSuperclass();
                String clazzName = clazz.getName();
                //跳过系统的类（肯定不会有EventBus的东西），来提高性能
                if (clazzName.startsWith("java.") || clazzName.startsWith("javax.") || clazzName.startsWith("android.")) {
                    clazz = null;
                }
            }
        }
    }
}
