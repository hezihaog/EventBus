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

import java.util.ArrayList;
import java.util.List;

/**
 * 封装等待发送事件Api的类，相当于一个消息，类似Handler的Message对象，内部有对象池和事件
 */
final class PendingPost {
    /**
     * 队列
     */
    private final static List<PendingPost> pendingPostPool = new ArrayList<PendingPost>();
    /**
     * 事件
     */
    Object event;
    /**
     * 订阅者信息
     */
    Subscription subscription;
    /**
     * 下一个要发送的信息
     */
    PendingPost next;

    private PendingPost(Object event, Subscription subscription) {
        this.event = event;
        this.subscription = subscription;
    }

    /**
     * 从池子中获取一个消息
     *
     * @param subscription 订阅信息
     * @param event        事件
     */
    static PendingPost obtainPendingPost(Subscription subscription, Object event) {
        synchronized (pendingPostPool) {
            int size = pendingPostPool.size();
            if (size > 0) {
                PendingPost pendingPost = pendingPostPool.remove(size - 1);
                //对字段赋值
                pendingPost.event = event;
                pendingPost.subscription = subscription;
                pendingPost.next = null;
                return pendingPost;
            }
        }
        return new PendingPost(event, subscription);
    }

    /**
     * 回收
     *
     * @param pendingPost 包裹了订阅者信息的消息
     */
    static void releasePendingPost(PendingPost pendingPost) {
        //重置字段
        pendingPost.event = null;
        pendingPost.subscription = null;
        pendingPost.next = null;
        //将对象放回对象池
        synchronized (pendingPostPool) {
            //这里对池子大小做限制，不然会不断让池容量增长
            if (pendingPostPool.size() < 10000) {
                pendingPostPool.add(pendingPost);
            }
        }
    }
}