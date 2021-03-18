/*
 * Copyright (C) 2017 The Android Open Source Project
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

package org.telegram.messenger.infra;

import static org.telegram.messenger.infra.PooledLambdaImpl.acquire;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.telegram.messenger.infra.PooledLambdaImpl.LambdaType.ReturnType;
import org.telegram.messenger.infra.PooledLambdaImpl.TriConsumer;

/**
 * A recyclable anonymous function.
 * Allows obtaining {@link Function}s/{@link Runnable}s/{@link Supplier}s/etc. without allocating a
 * new instance each time
 *
 * This exploits the mechanic that stateless lambdas (such as plain/non-bound method references)
 * get translated into a singleton instance, making it possible to create a recyclable container
 * ({@link PooledLambdaImpl}) holding a reference to such a singleton function, as well as
 * (possibly partial) arguments required for its invocation.
 *
 * To obtain an instance, use one of the factory methods in this class.
 *
 * You can call {@link #recycleOnUse} to make the instance automatically recycled upon invocation,
 * making if effectively <b>one-time use</b>.
 * This is often the behavior you want, as it allows to not worry about manual recycling.
 * Some notable examples: {@link android.os.Handler#post(Runnable)},
 * {@link android.app.Activity#runOnUiThread(Runnable)}, {@link android.view.View#post(Runnable)}
 *
 * For factories of functions that take further arguments, the corresponding 'missing' argument's
 * position is marked by an argument of type {@link ArgumentPlaceholder} with the type parameter
 * corresponding to missing argument's type.
 * You can fill the 'missing argument' spot with {@link #__()}
 * (which is the factory function for {@link ArgumentPlaceholder})
 *
 * NOTE: It is highly recommended to <b>only</b> use {@code ClassName::methodName}
 * (aka unbounded method references) as the 1st argument for any of the
 * factories ({@code obtain*(...)}) to avoid unwanted allocations.
 * This means <b>not</b> using:
 * <ul>
 *     <li>{@code someVar::methodName} or {@code this::methodName} as it captures the reference
 *     on the left of {@code ::}, resulting in an allocation on each evaluation of such
 *     bounded method references</li>
 *
 *     <li>A lambda expression, e.g. {@code () -> toString()} due to how easy it is to accidentally
 *     capture state from outside. In the above lambda expression for example, no variable from
 *     outer scope is explicitly mentioned, yet one is still captured due to {@code toString()}
 *     being an equivalent of {@code this.toString()}</li>
 * </ul>
 *
 * @hide
 */
@SuppressWarnings({ "unchecked", "unused", "WeakerAccess" })
interface PooledLambda {

    /**
     * Recycles this instance. No-op if already recycled.
     */
    void recycle();

    /**
     * Makes this instance automatically {@link #recycle} itself after the first call.
     *
     * @return this instance for convenience
     */
    PooledLambda recycleOnUse();

    // Factories

    /**
     * @return {@link ArgumentPlaceholder} with the inferred type parameter value
     */
    static <R> ArgumentPlaceholder<R> __() {
        return (ArgumentPlaceholder<R>) ArgumentPlaceholder.INSTANCE;
    }

    /**
     * @param typeHint the explicitly specified type of the missing argument
     * @return {@link ArgumentPlaceholder} with the specified type parameter value
     */
    static <R> ArgumentPlaceholder<R> __(Class<R> typeHint) {
        return __();
    }


    /**
     * {@link PooledRunnable} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @return a {@link PooledRunnable}, equivalent to lambda:
     *         {@code () -> function(arg1) }
     */
    static <A> PooledRunnable obtainRunnable(
            Consumer<? super A> function,
            A arg1) {
        return acquire(PooledLambdaImpl.sPool,
                       function, 1, 0, ReturnType.VOID, arg1, null, null, null, null, null, null, null,
                       null, null, null);
    }


    /**
     * {@link PooledRunnable} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @return a {@link PooledRunnable}, equivalent to lambda:
     *         {@code () -> function(arg1, arg2, arg3) }
     */
    static <A, B, C> PooledRunnable obtainRunnable(
            TriConsumer<? super A, ? super B, ? super C> function,
            A arg1, B arg2, C arg3) {
        return acquire(PooledLambdaImpl.sPool,
                       function, 3, 0, ReturnType.VOID, arg1, arg2, arg3, null, null, null, null, null,
                       null, null, null);
    }
}
