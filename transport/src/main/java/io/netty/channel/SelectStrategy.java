/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.util.IntSupplier;

/**
 * Select strategy interface.
 * <trans> 定义选择策略的接口 </trans>
 *
 * Provides the ability to control the behavior of the select loop.
 * For example a blocking select operation can be delayed or skipped entirely if there are events to process immediately.
 * <trans>
 *     控制select loop的行为.比如说一个阻塞的selector产生了一个事件,那么这个事件可以选择延迟处理或者直接跳过
 * </trans>
 */
public interface SelectStrategy {

    /**
     * Indicates a blocking select should follow.
     * <trans> 阻塞等待select返回 </trans>
     */
    int SELECT = -1;
    /**
     * Indicates the IO loop should be retried, no blocking select to follow directly.
     * <trans> select循环重试,不阻塞接下来的操作.就是跳过的意思 </trans>
     */
    int CONTINUE = -2;
    /**
     * Indicates the IO loop to poll for new events without blocking.
     * <trans> IO循环不阻塞的去获取事件 </trans>
     */
    int BUSY_WAIT = -3;

    /**
     * The {@link SelectStrategy} can be used to steer the outcome of a potential select
     * call.
     * <trans>
     *     用于计算select调用的SelectStrategy
     * </trans>
     *
     * @param selectSupplier The supplier with the result of a select result.
     * <trans> 获取select结果的supplier </trans>
     *
     * @param hasTasks true if tasks are waiting to be processed.
     * <trans> 如果此时有task正在等待被执行 </trans>
     *
     * @return {@link #SELECT} if the next step should be blocking select {@link #CONTINUE} if
     *         the next step should be to not select but rather jump back to the IO loop and try
     *         again. Any value >= 0 is treated as an indicator that work needs to be done.
     * <trans>
     *     当返回SELECT时,表示线程将被阻塞.
     *     当返回CONTINUE时，表示不进行select选择，重新开始新的循环
     *     返回值value>=0时都表示将进行select选择.
     * </trans>
     */
    int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception;
}
