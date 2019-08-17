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

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * EventBus is a central publish/subscribe event system for Android. Events are posted ({@link #post(Object)}) to the
 * bus, which delivers it to subscribers that have a matching handler method for the event type. To receive events,
 * subscribers must register themselves to the bus using {@link #register(Object)}. Once registered, subscribers
 * receive events until {@link #unregister(Object)} is called. Event handling methods must be annotated by
 * {@link Subscribe}, must be public, return nothing (void), and have exactly one parameter
 * (the event).
 *
 * @author Markus Junginger, greenrobot
 */
public class EventBus {

    /**
     * Log tag, apps may override it.
     */
    public static String TAG = "EventBus";

    static volatile EventBus defaultInstance;

    private static final EventBusBuilder DEFAULT_BUILDER = new EventBusBuilder();
    private static final Map<Class<?>, List<Class<?>>> eventTypesCache = new HashMap<>();

    /**
     * 事件类型和订阅它的订阅者的订阅信息（包含订阅者对象和订阅方法），一对多关系，一个事件类型，对应多个订阅者信息
     */
    private final Map<Class<?>, CopyOnWriteArrayList<Subscription>> subscriptionsByEventType;
    /**
     * 订阅者和它所订阅的事件类型映射，一对多关系，一个订阅者对应多个事件
     */
    private final Map<Object, List<Class<?>>> typesBySubscriber;
    /**
     * 粘性事件映射表，一对一关系，一个事件对应一个最近发送的事件对象
     */
    private final Map<Class<?>, Object> stickyEvents;

    /**
     * ThreadLocal类型，保存当前线程的发送状态类，每个线程都有一份PostingThreadState
     */
    private final ThreadLocal<PostingThreadState> currentPostingThreadState = new ThreadLocal<PostingThreadState>() {
        @Override
        protected PostingThreadState initialValue() {
            return new PostingThreadState();
        }
    };

    // @Nullable
    private final MainThreadSupport mainThreadSupport;
    // @Nullable
    private final Poster mainThreadPoster;
    private final BackgroundPoster backgroundPoster;
    private final AsyncPoster asyncPoster;
    private final SubscriberMethodFinder subscriberMethodFinder;
    private final ExecutorService executorService;

    private final boolean throwSubscriberException;
    private final boolean logSubscriberExceptions;
    private final boolean logNoSubscriberMessages;
    private final boolean sendSubscriberExceptionEvent;
    private final boolean sendNoSubscriberEvent;
    private final boolean eventInheritance;

    private final int indexCount;
    private final Logger logger;

    /**
     * Convenience singleton for apps using a process-wide EventBus instance.
     */
    public static EventBus getDefault() {
        EventBus instance = defaultInstance;
        if (instance == null) {
            synchronized (EventBus.class) {
                instance = EventBus.defaultInstance;
                if (instance == null) {
                    instance = EventBus.defaultInstance = new EventBus();
                }
            }
        }
        return instance;
    }

    public static EventBusBuilder builder() {
        return new EventBusBuilder();
    }

    /**
     * For unit test primarily.
     */
    public static void clearCaches() {
        SubscriberMethodFinder.clearCaches();
        eventTypesCache.clear();
    }

    /**
     * Creates a new EventBus instance; each instance is a separate scope in which events are delivered. To use a
     * central bus, consider {@link #getDefault()}.
     */
    public EventBus() {
        this(DEFAULT_BUILDER);
    }

    EventBus(EventBusBuilder builder) {
        logger = builder.getLogger();
        subscriptionsByEventType = new HashMap<>();
        typesBySubscriber = new HashMap<>();
        stickyEvents = new ConcurrentHashMap<>();
        mainThreadSupport = builder.getMainThreadSupport();
        mainThreadPoster = mainThreadSupport != null ? mainThreadSupport.createPoster(this) : null;
        backgroundPoster = new BackgroundPoster(this);
        asyncPoster = new AsyncPoster(this);
        indexCount = builder.subscriberInfoIndexes != null ? builder.subscriberInfoIndexes.size() : 0;
        subscriberMethodFinder = new SubscriberMethodFinder(builder.subscriberInfoIndexes,
                builder.strictMethodVerification, builder.ignoreGeneratedIndex);
        logSubscriberExceptions = builder.logSubscriberExceptions;
        logNoSubscriberMessages = builder.logNoSubscriberMessages;
        sendSubscriberExceptionEvent = builder.sendSubscriberExceptionEvent;
        sendNoSubscriberEvent = builder.sendNoSubscriberEvent;
        throwSubscriberException = builder.throwSubscriberException;
        eventInheritance = builder.eventInheritance;
        executorService = builder.executorService;
    }

    /**
     * 订阅事件
     */
    public void register(Object subscriber) {
        Class<?> subscriberClass = subscriber.getClass();
        //查找订阅者所有的订阅方法
        List<SubscriberMethod> subscriberMethods = subscriberMethodFinder.findSubscriberMethods(subscriberClass);
        synchronized (this) {
            //遍历订阅的方法列表，对每个订阅方法都进行注册
            for (SubscriberMethod subscriberMethod : subscriberMethods) {
                subscribe(subscriber, subscriberMethod);
            }
        }
    }

    /**
     * 将订阅者和订阅方法执行绑定
     *
     * @param subscriber       订阅者
     * @param subscriberMethod 订阅方法信息对象
     */
    private void subscribe(Object subscriber, SubscriberMethod subscriberMethod) {
        //获取事件类型
        Class<?> eventType = subscriberMethod.eventType;
        //新建Subscription订阅信息类
        Subscription newSubscription = new Subscription(subscriber, subscriberMethod);
        //从subscriptionsByEventType中查找订阅信息列表
        CopyOnWriteArrayList<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        //没有，则创建一个，并放到subscriptionsByEventType中
        if (subscriptions == null) {
            subscriptions = new CopyOnWriteArrayList<>();
            subscriptionsByEventType.put(eventType, subscriptions);
        } else {
            //已经调用了register方法订阅过了，不能重复订阅
            if (subscriptions.contains(newSubscription)) {
                throw new EventBusException("Subscriber " + subscriber.getClass() + " already registered to event "
                        + eventType);
            }
        }

        //优先级排序，优先级越高，越在List列表中靠前
        int size = subscriptions.size();
        for (int i = 0; i <= size; i++) {
            if (i == size || subscriberMethod.priority > subscriptions.get(i).subscriberMethod.priority) {
                subscriptions.add(i, newSubscription);
                break;
            }
        }
        //从typesBySubscriber中用订阅者获取它订阅的事件类型列表
        List<Class<?>> subscribedEvents = typesBySubscriber.get(subscriber);
        //还没有注册过，所以时间类型列表subscribedEvents为空，为空则创建一个
        if (subscribedEvents == null) {
            subscribedEvents = new ArrayList<>();
            //创建完毕，再保存到Map
            typesBySubscriber.put(subscriber, subscribedEvents);
        }
        //将事件类型添加到事件类型列表中
        subscribedEvents.add(eventType);

        //判断订阅方法是否是粘性的
        if (subscriberMethod.sticky) {
            //判断是否需要发送子类事件时也发送父类事件，默认为true，如果事件POJO不会继承，建议设置为false来提高性能
            if (eventInheritance) {
                // Existing sticky events of all subclasses of eventType have to be considered.
                // Note: Iterating over all events may be inefficient with lots of sticky events,
                // thus data structure should be changed to allow a more efficient lookup
                // (e.g. an additional map storing sub classes of super classes: Class -> List<Class>).
                Set<Map.Entry<Class<?>, Object>> entries = stickyEvents.entrySet();
                for (Map.Entry<Class<?>, Object> entry : entries) {
                    Class<?> candidateEventType = entry.getKey();
                    if (eventType.isAssignableFrom(candidateEventType)) {
                        Object stickyEvent = entry.getValue();
                        checkPostStickyEventToSubscription(newSubscription, stickyEvent);
                    }
                }
            } else {
                //粘性事件意思是订阅时，如果之前有发送过粘性事件则马上回调订阅方法
                //从粘性事件列表以事件类型中获取粘性事件POJO实例（所有粘性事件都会保存最近一份到内存）
                Object stickyEvent = stickyEvents.get(eventType);
                checkPostStickyEventToSubscription(newSubscription, stickyEvent);
            }
        }
    }

    /**
     * 检查粘性事件对象，以及将粘性事件发送到订阅者
     *
     * @param newSubscription 订阅信息
     * @param stickyEvent     粘性事件
     */
    private void checkPostStickyEventToSubscription(Subscription newSubscription, Object stickyEvent) {
        //没有发送过这个类型的粘性事件，那么就不做任何操作
        if (stickyEvent != null) {
            // If the subscriber is trying to abort the event, it will fail (event is not tracked in posting state)
            // --> Strange corner case, which we don't take care of here.
            //不为空，那么将这个粘性事件发送给订阅者
            postToSubscription(newSubscription, stickyEvent, isMainThread());
        }
    }

    /**
     * 判断当前是否是主线程
     */
    private boolean isMainThread() {
        return mainThreadSupport != null ? mainThreadSupport.isMainThread() : true;
    }

    public synchronized boolean isRegistered(Object subscriber) {
        return typesBySubscriber.containsKey(subscriber);
    }

    /**
     * 使用事件类型，注销订阅
     *
     * @param subscriber 订阅者
     * @param eventType  事件类型
     */
    private void unsubscribeByEventType(Object subscriber, Class<?> eventType) {
        //获取事件类型的所有订阅者的订阅定系
        List<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        //没有订阅者忽略
        if (subscriptions != null) {
            //遍历订阅者的订阅信息列表
            int size = subscriptions.size();
            for (int i = 0; i < size; i++) {
                //找到本次要取消订阅的订阅者信息
                Subscription subscription = subscriptions.get(i);
                if (subscription.subscriber == subscriber) {
                    //设置为取消订阅
                    subscription.active = false;
                    //从列表中移除
                    subscriptions.remove(i);
                    i--;
                    size--;
                }
            }
        }
    }

    /**
     * 注销事件注册
     *
     * @param subscriber 订阅者
     */
    public synchronized void unregister(Object subscriber) {
        //获取订阅者订阅的所有事件类型
        List<Class<?>> subscribedTypes = typesBySubscriber.get(subscriber);
        //没有订阅过，忽略
        if (subscribedTypes != null) {
            //遍历订阅的事件类型，取消注册
            for (Class<?> eventType : subscribedTypes) {
                unsubscribeByEventType(subscriber, eventType);
            }
            //从订阅者列表中移除
            typesBySubscriber.remove(subscriber);
        } else {
            logger.log(Level.WARNING, "Subscriber to unregister was not registered before: " + subscriber.getClass());
        }
    }

    /**
     * 发送事件
     */
    public void post(Object event) {
        //获取当前线程的发送状态
        PostingThreadState postingState = currentPostingThreadState.get();
        //获取发送状态的队列
        List<Object> eventQueue = postingState.eventQueue;
        //事件入队
        eventQueue.add(event);
        //如果没有在发送，则马上发送
        if (!postingState.isPosting) {
            //对postingState做一些配置
            postingState.isMainThread = isMainThread();
            postingState.isPosting = true;
            if (postingState.canceled) {
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            try {
                //队列不为空，则发送事件
                while (!eventQueue.isEmpty()) {
                    postSingleEvent(eventQueue.remove(0), postingState);
                }
            } finally {
                postingState.isPosting = false;
                postingState.isMainThread = false;
            }
        }
    }

    /**
     * Called from a subscriber's event handling method, further event delivery will be canceled. Subsequent
     * subscribers
     * won't receive the event. Events are usually canceled by higher priority subscribers (see
     * {@link Subscribe#priority()}). Canceling is restricted to event handling methods running in posting thread
     * {@link ThreadMode#POSTING}.
     */
    public void cancelEventDelivery(Object event) {
        PostingThreadState postingState = currentPostingThreadState.get();
        if (!postingState.isPosting) {
            throw new EventBusException(
                    "This method may only be called from inside event handling methods on the posting thread");
        } else if (event == null) {
            throw new EventBusException("Event may not be null");
        } else if (postingState.event != event) {
            throw new EventBusException("Only the currently handled event may be aborted");
        } else if (postingState.subscription.subscriberMethod.threadMode != ThreadMode.POSTING) {
            throw new EventBusException(" event handlers may only abort the incoming event");
        }

        postingState.canceled = true;
    }

    /**
     * 发送粘性事件
     *
     * @param event 事件
     */
    public void postSticky(Object event) {
        //保存事件到粘性事件映射表
        synchronized (stickyEvents) {
            stickyEvents.put(event.getClass(), event);
        }
        //马上发送事件，和普通事件一样，粘性事件的特点是register()订阅时，马上检查是否有存在的粘性事件，有则马上回调
        post(event);
    }

    /**
     * Gets the most recent sticky event for the given type.
     *
     * @see #postSticky(Object)
     */
    public <T> T getStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.get(eventType));
        }
    }

    /**
     * Remove and gets the recent sticky event for the given event type.
     *
     * @see #postSticky(Object)
     */
    public <T> T removeStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.remove(eventType));
        }
    }

    /**
     * Removes the sticky event if it equals to the given event.
     *
     * @return true if the events matched and the sticky event was removed.
     */
    public boolean removeStickyEvent(Object event) {
        synchronized (stickyEvents) {
            Class<?> eventType = event.getClass();
            Object existingEvent = stickyEvents.get(eventType);
            if (event.equals(existingEvent)) {
                stickyEvents.remove(eventType);
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Removes all sticky events.
     */
    public void removeAllStickyEvents() {
        synchronized (stickyEvents) {
            stickyEvents.clear();
        }
    }

    public boolean hasSubscriberForEvent(Class<?> eventClass) {
        List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
        if (eventTypes != null) {
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                CopyOnWriteArrayList<Subscription> subscriptions;
                synchronized (this) {
                    subscriptions = subscriptionsByEventType.get(clazz);
                }
                if (subscriptions != null && !subscriptions.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 发送单个事件
     *
     * @param event        事件对象
     * @param postingState 发送状态
     */
    private void postSingleEvent(Object event, PostingThreadState postingState) throws Error {
        Class<?> eventClass = event.getClass();
        boolean subscriptionFound = false;
        //判断是否发送子类事件时也发送父类事件
        if (eventInheritance) {
            List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                subscriptionFound |= postSingleEventForEventType(event, postingState, clazz);
            }
        } else {
            subscriptionFound = postSingleEventForEventType(event, postingState, eventClass);
        }
        //处理没有订阅者的情况
        if (!subscriptionFound) {
            if (logNoSubscriberMessages) {
                logger.log(Level.FINE, "No subscribers registered for event " + eventClass);
            }
            //没有订阅者，发送一个NoSubscriberEvent事件
            if (sendNoSubscriberEvent && eventClass != NoSubscriberEvent.class &&
                    eventClass != SubscriberExceptionEvent.class) {
                post(new NoSubscriberEvent(this, event));
            }
        }
    }

    /**
     * 按事件类型，发送单个事件
     *
     * @param event        事件对象
     * @param postingState 发送状态
     * @param eventClass   事件类型Class
     * @return 是否有订阅者订阅者这个事件
     */
    private boolean postSingleEventForEventType(Object event, PostingThreadState postingState, Class<?> eventClass) {
        CopyOnWriteArrayList<Subscription> subscriptions;
        synchronized (this) {
            //回查subscriptionsByEventType，获取要发送的这个事件的所有订阅者信息
            subscriptions = subscriptionsByEventType.get(eventClass);
        }
        //有订阅者订阅，则处理
        if (subscriptions != null && !subscriptions.isEmpty()) {
            //遍历订阅者，将事件发送给他们
            for (Subscription subscription : subscriptions) {
                postingState.event = event;
                postingState.subscription = subscription;
                //是否发送失败
                boolean aborted = false;
                try {
                    //发送事件
                    postToSubscription(subscription, event, postingState.isMainThread);
                    aborted = postingState.canceled;
                } finally {
                    //重置状态
                    postingState.event = null;
                    postingState.subscription = null;
                    postingState.canceled = false;
                }
                if (aborted) {
                    break;
                }
            }
            return true;
        }
        //没有订阅者订阅
        return false;
    }

    /**
     * 发送事件到订阅者
     *
     * @param subscription 订阅嘻嘻
     * @param event        事件类型
     * @param isMainThread 当时是否在主线程
     */
    private void postToSubscription(Subscription subscription, Object event, boolean isMainThread) {
        //分类事件回调线程模式
        switch (subscription.subscriberMethod.threadMode) {
            //POSTING模式，直接在当前线程反射调用订阅者的订阅方法
            case POSTING:
                invokeSubscriber(subscription, event);
                break;
            case MAIN:
                //MAIN模式，保证在主线程回调订阅者的订阅方法
                //判断当前是否为主线程，直接调用即可
                if (isMainThread) {
                    invokeSubscriber(subscription, event);
                } else {
                    //当前不在主线程，将任务交给mainThreadPoster主线程发送器使用Handler发送
                    mainThreadPoster.enqueue(subscription, event);
                }
                break;
            case MAIN_ORDERED:
                //MAIN_ORDERED模式，保证主线程中回调订阅者的订阅方法，但是每次都是用Handler去post一个消息
                //所以即使当前已经是主线程了，也依然post一个消息给Handler排队执行
                if (mainThreadPoster != null) {
                    mainThreadPoster.enqueue(subscription, event);
                } else {
                    //不在安卓上使用EventBus，直接调用订阅者订阅方法
                    // temporary: technically not correct as poster not decoupled from subscriber
                    invokeSubscriber(subscription, event);
                }
                break;
            case BACKGROUND:
                //BACKGROUND模式，保证在子线程回调，如果当前已经在子线程，直接调用订阅者的订阅方法
                if (isMainThread) {
                    //不在子线程，将任务交给backgroundPoster
                    backgroundPoster.enqueue(subscription, event);
                } else {
                    //已经在子线程了，直接调用
                    invokeSubscriber(subscription, event);
                }
                break;
            case ASYNC:
                //ASYNC模式，无论在哪个线程都让asyncPoster执行任务，所以就算已经在线程中了，也新开一个线程执行
                asyncPoster.enqueue(subscription, event);
                break;
            default:
                throw new IllegalStateException("Unknown thread mode: " + subscription.subscriberMethod.threadMode);
        }
    }

    /**
     * Looks up all Class objects including super classes and interfaces. Should also work for interfaces.
     */
    private static List<Class<?>> lookupAllEventTypes(Class<?> eventClass) {
        synchronized (eventTypesCache) {
            List<Class<?>> eventTypes = eventTypesCache.get(eventClass);
            if (eventTypes == null) {
                eventTypes = new ArrayList<>();
                Class<?> clazz = eventClass;
                while (clazz != null) {
                    eventTypes.add(clazz);
                    addInterfaces(eventTypes, clazz.getInterfaces());
                    clazz = clazz.getSuperclass();
                }
                eventTypesCache.put(eventClass, eventTypes);
            }
            return eventTypes;
        }
    }

    /**
     * Recurses through super interfaces.
     */
    static void addInterfaces(List<Class<?>> eventTypes, Class<?>[] interfaces) {
        for (Class<?> interfaceClass : interfaces) {
            if (!eventTypes.contains(interfaceClass)) {
                eventTypes.add(interfaceClass);
                addInterfaces(eventTypes, interfaceClass.getInterfaces());
            }
        }
    }

    /**
     * 传入PendingPost的方式，调动订阅者订阅方法
     */
    void invokeSubscriber(PendingPost pendingPost) {
        Object event = pendingPost.event;
        Subscription subscription = pendingPost.subscription;
        PendingPost.releasePendingPost(pendingPost);
        //有一个订阅者订阅事件，则发送
        if (subscription.active) {
            invokeSubscriber(subscription, event);
        }
    }

    /**
     * 发射调用订阅者的订阅方法
     *
     * @param subscription 订阅信息
     * @param event        事件对象
     */
    void invokeSubscriber(Subscription subscription, Object event) {
        try {
            //拿到订阅信息的method对象，invoke()反射调用
            subscription.subscriberMethod.method.invoke(subscription.subscriber, event);
        } catch (InvocationTargetException e) {
            handleSubscriberException(subscription, event, e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unexpected exception", e);
        }
    }

    private void handleSubscriberException(Subscription subscription, Object event, Throwable cause) {
        if (event instanceof SubscriberExceptionEvent) {
            if (logSubscriberExceptions) {
                // Don't send another SubscriberExceptionEvent to avoid infinite event recursion, just log
                logger.log(Level.SEVERE, "SubscriberExceptionEvent subscriber " + subscription.subscriber.getClass()
                        + " threw an exception", cause);
                SubscriberExceptionEvent exEvent = (SubscriberExceptionEvent) event;
                logger.log(Level.SEVERE, "Initial event " + exEvent.causingEvent + " caused exception in "
                        + exEvent.causingSubscriber, exEvent.throwable);
            }
        } else {
            if (throwSubscriberException) {
                throw new EventBusException("Invoking subscriber failed", cause);
            }
            if (logSubscriberExceptions) {
                logger.log(Level.SEVERE, "Could not dispatch event: " + event.getClass() + " to subscribing class "
                        + subscription.subscriber.getClass(), cause);
            }
            if (sendSubscriberExceptionEvent) {
                SubscriberExceptionEvent exEvent = new SubscriberExceptionEvent(this, cause, event,
                        subscription.subscriber);
                post(exEvent);
            }
        }
    }

    /**
     * 发送线程的状态
     */
    final static class PostingThreadState {
        /**
         * 事件队列
         */
        final List<Object> eventQueue = new ArrayList<>();
        /**
         * 是否正在发送中
         */
        boolean isPosting;
        /**
         * 是否主线程
         */
        boolean isMainThread;
        Subscription subscription;
        Object event;
        boolean canceled;
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * For internal use only.
     */
    public Logger getLogger() {
        return logger;
    }

    // Just an idea: we could provide a callback to post() to be notified, an alternative would be events, of course...
    /* public */interface PostCallback {
        void onPostCompleted(List<SubscriberExceptionEvent> exceptionEvents);
    }

    @Override
    public String toString() {
        return "EventBus[indexCount=" + indexCount + ", eventInheritance=" + eventInheritance + "]";
    }
}
