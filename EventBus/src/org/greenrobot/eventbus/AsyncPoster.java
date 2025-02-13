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


/**
 * 子线程并发执行器，实现了Runnable,、Poster接口
 */
class AsyncPoster implements Runnable, Poster {
    /**
     * 消息队列
     */
    private final PendingPostQueue queue;
    private final EventBus eventBus;

    AsyncPoster(EventBus eventBus) {
        this.eventBus = eventBus;
        queue = new PendingPostQueue();
    }

    @Override
    public void enqueue(Subscription subscription, Object event) {
        //入队，获取一个缓存的PendingPost消息对象，重新初始化
        PendingPost pendingPost = PendingPost.obtainPendingPost(subscription, event);
        //将消息入队
        queue.enqueue(pendingPost);
        //获取执行器执行
        eventBus.getExecutorService().execute(this);
    }

    @Override
    public void run() {
        //获取一个PendingPost消息
        PendingPost pendingPost = queue.poll();
        if (pendingPost == null) {
            throw new IllegalStateException("No pending post available");
        }
        //反射调用订阅者的订阅方法
        eventBus.invokeSubscriber(pendingPost);
    }
}