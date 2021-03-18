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

import android.text.TextUtils;
import android.util.Log;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.telegram.messenger.support.ArrayUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pools.SimplePool;

/**
 * @see PooledLambda
 * @hide
 */
final class PooledLambdaImpl<R> implements PooledLambda {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "PooledLambdaImpl";

    private static final int MAX_ARGS = 11;

    private static final int MAX_POOL_SIZE = 50;

    public static class SynchronizedPool<T> extends SimplePool<T> {
        private final Object mLock;

        /**
         * Creates a new instance.
         *
         * @param maxPoolSize The max pool size.
         *
         * @throws IllegalArgumentException If the max pool size is less than zero.
         */
        public SynchronizedPool(int maxPoolSize, Object lock) {
            super(maxPoolSize);
            mLock = lock;
        }

        @Override
        public T acquire() {
            synchronized (mLock) {
                return super.acquire();
            }
        }

        @Override
        public boolean release(@NonNull T element) {
            synchronized (mLock) {
                return super.release(element);
            }
        }
    }

    static class Pool extends SynchronizedPool<PooledLambdaImpl> {

        public Pool(Object lock) {
            super(MAX_POOL_SIZE, lock);
        }
    }

    static final Pool sPool = new Pool(new Object());

    private PooledLambdaImpl() {}

    /**
     * The function reference to be invoked
     *
     * May be the return value itself in case when an immediate result constant is provided instead
     */
    Object mFunc;

    /**
     * A primitive result value to be immediately returned on invocation instead of calling
     * {@link #mFunc}
     */
    long mConstValue;

    /**
     * Arguments for {@link #mFunc}
     */
    @Nullable
    Object[] mArgs = null;

    /**
     * Flag for {@link #mFlags}
     *
     * Indicates whether this instance is recycled
     */
    private static final int FLAG_RECYCLED = 1 << MAX_ARGS;

    /**
     * Flag for {@link #mFlags}
     *
     * Indicates whether this instance should be immediately recycled on invocation
     * (as requested via {@link PooledLambda#recycleOnUse()}) or not(default)
     */
    private static final int FLAG_RECYCLE_ON_USE = 1 << (MAX_ARGS + 1);

    /** @see #mFlags */
    static final int MASK_EXPOSED_AS = LambdaType.MASK << (MAX_ARGS + 3);

    /** @see #mFlags */
    static final int MASK_FUNC_TYPE = LambdaType.MASK <<
            (MAX_ARGS + 3 + LambdaType.MASK_BIT_COUNT);

    /**
     * Bit schema:
     * AAAAAAAAAAABCDEEEEEEFFFFFF
     *
     * Where:
     * A - whether {@link #mArgs arg} at corresponding index was specified at
     * {@link #acquire creation time} (0) or {@link #invoke invocation time} (1)
     * B - {@link #FLAG_RECYCLED}
     * C - {@link #FLAG_RECYCLE_ON_USE}
     * E - {@link LambdaType} representing the type of the lambda returned to the caller from a
     * factory method
     * F - {@link LambdaType} of {@link #mFunc} as resolved when calling a factory method
     */
    int mFlags = 0;

    @Override
    public PooledLambda recycleOnUse() {
        if (DEBUG) Log.i(LOG_TAG, this + ".recycleOnUse()");
        mFlags |= FLAG_RECYCLE_ON_USE;
        return this;
    }

    public void recycle() {
        if (DEBUG) Log.i(LOG_TAG, this + ".recycle()");
        if (!isRecycled()) doRecycle();
    }

    private void doRecycle() {
        if (DEBUG) Log.i(LOG_TAG, this + ".doRecycle()");
        Pool pool = PooledLambdaImpl.sPool;

        mFunc = null;
        if (mArgs != null) Arrays.fill(mArgs, null);
        mFlags = FLAG_RECYCLED;
        mConstValue = 0L;

        pool.release(this);
    }

    R invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7,
            Object a8, Object a9, Object a10, Object a11) {
        checkNotRecycled();
        if (DEBUG) {
            Log.i(LOG_TAG, this + ".invoke("
                    + commaSeparateFirstN(
                            new Object[] { a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11 },
                            LambdaType.decodeArgCount(getFlags(MASK_EXPOSED_AS)))
                    + ")");
        }
        final boolean notUsed = fillInArg(a1) && fillInArg(a2) && fillInArg(a3) && fillInArg(a4)
                && fillInArg(a5) && fillInArg(a6) && fillInArg(a7) && fillInArg(a8)
                && fillInArg(a9) && fillInArg(a10) && fillInArg(a11);
        int argCount = LambdaType.decodeArgCount(getFlags(MASK_FUNC_TYPE));
        if (argCount != LambdaType.MASK_ARG_COUNT) {
            for (int i = 0; i < argCount; i++) {
                if (mArgs[i] == ArgumentPlaceholder.INSTANCE) {
                    throw new IllegalStateException("Missing argument #" + i + " among "
                            + Arrays.toString(mArgs));
                }
            }
        }
        try {
            return doInvoke();
        } finally {
            if (isRecycleOnUse()) {
                doRecycle();
            } else if (!isRecycled()) {
                int argsSize = ArrayUtils.size(mArgs);
                for (int i = 0; i < argsSize; i++) {
                    popArg(i);
                }
            }
        }
    }

    private boolean fillInArg(Object invocationArg) {
        int argsSize = ArrayUtils.size(mArgs);
        for (int i = 0; i < argsSize; i++) {
            if (mArgs[i] == ArgumentPlaceholder.INSTANCE) {
                mArgs[i] = invocationArg;
                mFlags |= BitUtils.bitAt(i);
                return true;
            }
        }
        if (invocationArg != null && invocationArg != ArgumentPlaceholder.INSTANCE) {
            throw new IllegalStateException("No more arguments expected for provided arg "
                    + invocationArg + " among " + Arrays.toString(mArgs));
        }
        return false;
    }

    private void checkNotRecycled() {
        if (isRecycled()) throw new IllegalStateException("Instance is recycled: " + this);
    }

    @SuppressWarnings("unchecked")
    private R doInvoke() {
        final int funcType = getFlags(MASK_FUNC_TYPE);
        final int argCount = LambdaType.decodeArgCount(funcType);
        final int returnType = LambdaType.decodeReturnType(funcType);

        switch (argCount) {
            case LambdaType.MASK_ARG_COUNT: {
                switch (returnType) {
                    case LambdaType.ReturnType.INT: return (R) (Integer) getAsInt();
                    case LambdaType.ReturnType.LONG: return (R) (Long) getAsLong();
                    case LambdaType.ReturnType.DOUBLE: return (R) (Double) getAsDouble();
                    default: return (R) mFunc;
                }
            }
            case 0: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((Runnable) mFunc).run();
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN:
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((Supplier) mFunc).get();
                    }
                }
            } break;
            case 1: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((Consumer) mFunc).accept(popArg(0));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((Predicate) mFunc).test(popArg(0));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((Function) mFunc).apply(popArg(0));
                    }
                }
            } break;
            case 2: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((BiConsumer) mFunc).accept(popArg(0), popArg(1));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((BiPredicate) mFunc).test(popArg(0), popArg(1));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((BiFunction) mFunc).apply(popArg(0), popArg(1));
                    }
                }
            } break;
            case 3: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((TriConsumer) mFunc).accept(popArg(0), popArg(1), popArg(2));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((TriPredicate) mFunc).test(
                                popArg(0), popArg(1), popArg(2));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((TriFunction) mFunc).apply(popArg(0), popArg(1), popArg(2));
                    }
                }
            } break;
            case 4: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((QuadConsumer) mFunc).accept(popArg(0), popArg(1), popArg(2), popArg(3));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((QuadPredicate) mFunc).test(
                                popArg(0), popArg(1), popArg(2), popArg(3));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((QuadFunction) mFunc).apply(
                                popArg(0), popArg(1), popArg(2), popArg(3));
                    }
                }
            } break;
        }
        throw new IllegalStateException("Unknown function type: " + LambdaType.toString(funcType));
    }

    private boolean isConstSupplier() {
        return LambdaType.decodeArgCount(getFlags(MASK_FUNC_TYPE)) == LambdaType.MASK_ARG_COUNT;
    }

    private Object popArg(int index) {
        Object result = mArgs[index];
        if (isInvocationArgAtIndex(index)) {
            mArgs[index] = ArgumentPlaceholder.INSTANCE;
            mFlags &= ~BitUtils.bitAt(index);
        }
        return result;
    }

    @Override
    public String toString() {
        if (isRecycled()) return "<recycled PooledLambda@" + hashCodeHex(this) + ">";

        StringBuilder sb = new StringBuilder();
        if (isConstSupplier()) {
            sb.append(getFuncTypeAsString()).append("(").append(doInvoke()).append(")");
        } else {
            Object func = mFunc;
            if (func instanceof PooledLambdaImpl) {
                sb.append(func);
            } else {
                sb.append(getFuncTypeAsString()).append("@").append(hashCodeHex(func));
            }
            sb.append("(");
            sb.append(commaSeparateFirstN(mArgs,
                    LambdaType.decodeArgCount(getFlags(MASK_FUNC_TYPE))));
            sb.append(")");
        }
        return sb.toString();
    }

    private String commaSeparateFirstN(@Nullable Object[] arr, int n) {
        if (arr == null) return "";
        return TextUtils.join(",", Arrays.copyOf(arr, n));
    }

    private static String hashCodeHex(Object o) {
        return Integer.toHexString(Objects.hashCode(o));
    }

    private String getFuncTypeAsString() {
        if (isRecycled()) return "<recycled>";
        if (isConstSupplier()) return "supplier";
        String name = LambdaType.toString(getFlags(MASK_EXPOSED_AS));
        if (name.endsWith("Consumer")) return "consumer";
        if (name.endsWith("Function")) return "function";
        if (name.endsWith("Predicate")) return "predicate";
        if (name.endsWith("Supplier")) return "supplier";
        if (name.endsWith("Runnable")) return "runnable";
        return name;
    }

    /**
     * Internal non-typesafe factory method for {@link PooledLambdaImpl}
     */
    static <E extends PooledLambda> E acquire(Pool pool, Object func,
            int fNumArgs, int numPlaceholders, int fReturnType, Object a, Object b, Object c,
            Object d, Object e, Object f, Object g, Object h, Object i, Object j, Object k) {
        PooledLambdaImpl r = acquire(pool);
        if (DEBUG) {
            Log.i(LOG_TAG,
                    "acquire(this = @" + hashCodeHex(r)
                            + ", func = " + func
                            + ", fNumArgs = " + fNumArgs
                            + ", numPlaceholders = " + numPlaceholders
                            + ", fReturnType = " + LambdaType.ReturnType.toString(fReturnType)
                            + ", a = " + a
                            + ", b = " + b
                            + ", c = " + c
                            + ", d = " + d
                            + ", e = " + e
                            + ", f = " + f
                            + ", g = " + g
                            + ", h = " + h
                            + ", i = " + i
                            + ", j = " + j
                            + ", k = " + k
                            + ")");
        }
        r.mFunc = Objects.requireNonNull(func);
        r.setFlags(MASK_FUNC_TYPE, LambdaType.encode(fNumArgs, fReturnType));
        r.setFlags(MASK_EXPOSED_AS, LambdaType.encode(numPlaceholders, fReturnType));
        if (ArrayUtils.size(r.mArgs) < fNumArgs) r.mArgs = new Object[fNumArgs];
        setIfInBounds(r.mArgs, 0, a);
        setIfInBounds(r.mArgs, 1, b);
        setIfInBounds(r.mArgs, 2, c);
        setIfInBounds(r.mArgs, 3, d);
        setIfInBounds(r.mArgs, 4, e);
        setIfInBounds(r.mArgs, 5, f);
        setIfInBounds(r.mArgs, 6, g);
        setIfInBounds(r.mArgs, 7, h);
        setIfInBounds(r.mArgs, 8, i);
        setIfInBounds(r.mArgs, 9, j);
        setIfInBounds(r.mArgs, 10, k);
        return (E) r;
    }

    static PooledLambdaImpl acquireConstSupplier(int type) {
        PooledLambdaImpl r = acquire(PooledLambdaImpl.sPool);
        int lambdaType = LambdaType.encode(LambdaType.MASK_ARG_COUNT, type);
        r.setFlags(PooledLambdaImpl.MASK_FUNC_TYPE, lambdaType);
        r.setFlags(PooledLambdaImpl.MASK_EXPOSED_AS, lambdaType);
        return r;
    }

    static PooledLambdaImpl acquire(Pool pool) {
        PooledLambdaImpl r = pool.acquire();
        if (r == null) r = new PooledLambdaImpl();
        r.mFlags &= ~FLAG_RECYCLED;
        return r;
    }

    private static void setIfInBounds(Object[] array, int i, Object a) {
        if (i < ArrayUtils.size(array)) array[i] = a;
    }

    public double getAsDouble() {
        return Double.longBitsToDouble(mConstValue);
    }

    public int getAsInt() {
        return (int) mConstValue;
    }

    public long getAsLong() {
        return mConstValue;
    }

    public String getTraceName() {
        return FunctionalUtils.getLambdaName(mFunc);
    }

    private boolean isRecycled() {
        return (mFlags & FLAG_RECYCLED) != 0;
    }

    private boolean isRecycleOnUse() {
        return (mFlags & FLAG_RECYCLE_ON_USE) != 0;
    }

    private boolean isInvocationArgAtIndex(int argIndex) {
        return (mFlags & (1 << argIndex)) != 0;
    }

    int getFlags(int mask) {
        return unmask(mask, mFlags);
    }

    void setFlags(int mask, int value) {
        mFlags &= ~mask;
        mFlags |= mask(mask, value);
    }

    /**
     * 0xFF000, 0xAB -> 0xAB000
     */
    private static int mask(int mask, int value) {
        return (value << Integer.numberOfTrailingZeros(mask)) & mask;
    }

    /**
     * 0xFF000, 0xAB123 -> 0xAB
     */
    private static int unmask(int mask, int bits) {
        return (bits & mask) / (1 << Integer.numberOfTrailingZeros(mask));
    }

    /**
     * Contract for encoding a supported lambda type in {@link #MASK_BIT_COUNT} bits
     */
    static class LambdaType {
        public static final int MASK_ARG_COUNT = 0b1111;
        public static final int MASK_RETURN_TYPE = 0b1110000;
        public static final int MASK = MASK_ARG_COUNT | MASK_RETURN_TYPE;
        public static final int MASK_BIT_COUNT = 7;

        static int encode(int argCount, int returnType) {
            return mask(MASK_ARG_COUNT, argCount) | mask(MASK_RETURN_TYPE, returnType);
        }

        static int decodeArgCount(int type) {
            return type & MASK_ARG_COUNT;
        }

        static int decodeReturnType(int type) {
            return unmask(MASK_RETURN_TYPE, type);
        }

        static String toString(int type) {
            int argCount = decodeArgCount(type);
            int returnType = decodeReturnType(type);
            if (argCount == 0) {
                if (returnType == ReturnType.VOID) return "Runnable";
                if (returnType == ReturnType.OBJECT || returnType == ReturnType.BOOLEAN) {
                    return "Supplier";
                }
            }
            return argCountPrefix(argCount) + ReturnType.lambdaSuffix(returnType);
        }

        private static String argCountPrefix(int argCount) {
            switch (argCount) {
                case MASK_ARG_COUNT: return "";
                case 0: return "";
                case 1: return "";
                case 2: return "Bi";
                case 3: return "Tri";
                case 4: return "Quad";
                case 5: return "Quint";
                default: return "" + argCount + "arg";
            }
        }

        static class ReturnType {
            public static final int VOID = 1;
            public static final int BOOLEAN = 2;
            public static final int OBJECT = 3;
            public static final int INT = 4;
            public static final int LONG = 5;
            public static final int DOUBLE = 6;

            static String toString(int returnType) {
                switch (returnType) {
                    case VOID: return "VOID";
                    case BOOLEAN: return "BOOLEAN";
                    case OBJECT: return "OBJECT";
                    case INT: return "INT";
                    case LONG: return "LONG";
                    case DOUBLE: return "DOUBLE";
                    default: return "" + returnType;
                }
            }

            static String lambdaSuffix(int type) {
                return prefix(type) + suffix(type);
            }

            private static String prefix(int type) {
                switch (type) {
                    case INT: return "Int";
                    case LONG: return "Long";
                    case DOUBLE: return "Double";
                    default: return "";
                }
            }

            private static String suffix(int type) {
                switch (type) {
                    case VOID: return "Consumer";
                    case BOOLEAN: return "Predicate";
                    case OBJECT: return "Function";
                    default: return "Supplier";
                }
            }
        }
    }

    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }

    public interface TriPredicate<A, B, C> {
        boolean test(A a, B b, C c);
    }

    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    public interface QuadConsumer<A, B, C, D> {
        void accept(A a, B b, C c, D d);
    }

    public interface QuadPredicate<A, B, C, D> {
        boolean test(A a, B b, C c, D d);
    }

    public interface QuadFunction<A, B, C, D, R> {
        R apply(A a, B b, C c, D d);
    }
}
