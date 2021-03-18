/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.tgnet;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.telegram.messenger.BuildVars;

public class TLObject {

    public int networkType;

    public boolean disableFree = false;
    private static final ThreadLocal<NativeByteBuffer> sizeCalculator = new ThreadLocal<NativeByteBuffer>() {
        @Override
        protected NativeByteBuffer initialValue() {
            return new NativeByteBuffer(true);
        }
    };

    public TLObject() {

    }

    public void readParams(AbstractSerializedData stream, boolean exception) {

    }

    public void serializeToStream(AbstractSerializedData stream) {

    }

    public TLObject deserializeResponse(AbstractSerializedData stream, int constructor, boolean exception) {
        return null;
    }

    public void freeResources() {

    }

    public int getObjectSize() {
        NativeByteBuffer byteBuffer = sizeCalculator.get();
        byteBuffer.rewind();
        serializeToStream(sizeCalculator.get());
        return byteBuffer.length();
    }

    @Override
    public String toString() {
        if (BuildVars.LOGS_ENABLED) {
            StringBuilder b = new StringBuilder(getClass().getSimpleName()).append("{");
            int count = 0;
            for (Field f : getClass().getFields()) {
                if (!isStaticField(f)) {
                    try {
                        if (count > 0)
                            b.append(", ");
                        b.append(f.getName()).append("=").append(f.get(this));
                    } catch (IllegalAccessException e) {
                        // pass, don't print
                    }
                    count++;
                }
            }
            b.append('}');
            return b.toString();
        } else {
            return super.toString();
        }
    }

    private boolean isStaticField(Field f) {
        return Modifier.isStatic(f.getModifiers());
    }
}
