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

import java.util.logging.Level;

/**
 * 子线程回调事件订阅的发送器，实现了Runnable和Poster接口
 */
final class BackgroundPoster implements Runnable, Poster {
    /**
     * 发送队列
     */
    private final PendingPostQueue queue;
    private final EventBus eventBus;

    private volatile boolean executorRunning;

    BackgroundPoster(EventBus eventBus) {
        this.eventBus = eventBus;
        queue = new PendingPostQueue();
    }

    @Override
    public void enqueue(Subscription subscription, Object event) {
        //获取一个消息，并将任务重新初始化
        PendingPost pendingPost = PendingPost.obtainPendingPost(subscription, event);
        synchronized (this) {
            //任务入队
            queue.enqueue(pendingPost);
            //如果没有执行，马上执行
            if (!executorRunning) {
                executorRunning = true;
                //获取配置的线程池执行器进行执行，将任务包裹到自身去执行
                eventBus.getExecutorService().execute(this);
            }
        }
    }

    @Override
    public void run() {
        try {
            try {
                //一直死循环执行
                while (true) {
                    //获取下一个消息
                    PendingPost pendingPost = queue.poll(1000);
                    if (pendingPost == null) {
                        synchronized (this) {
                            //同样要双重检查
                            pendingPost = queue.poll();
                            if (pendingPost == null) {
                                //没有事件了，跳出死循环
                                executorRunning = false;
                                return;
                            }
                        }
                    }
                    //调用订阅者
                    eventBus.invokeSubscriber(pendingPost);
                }
            } catch (InterruptedException e) {
                eventBus.getLogger().log(Level.WARNING, Thread.currentThread().getName() + " was interruppted", e);
            }
        } finally {
            //所有发送任务执行完毕，标志位置为false，下次再入队再继续执行
            executorRunning = false;
        }
    }
}