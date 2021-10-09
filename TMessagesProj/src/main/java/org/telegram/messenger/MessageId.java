package org.telegram.messenger;

import java.util.Objects;

//SG: Telegram has migrated the format of mid from long to int
public final class MessageId {
    public final int mid;
    public final long uid;

    private MessageId(final int mid, final long uid) {
        this.mid = mid;
        this.uid = uid;
    }

    public static MessageId of(int mid, long uid) {
        return new MessageId(mid, uid);
    }

    @Override public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        final MessageId messageId = (MessageId) o;

        if (mid != messageId.mid) return false;
        return uid == messageId.uid;
    }

    @Override public int hashCode() {
        int result = mid;
        result = 31 * result + (int) (uid ^ (uid >>> 32));
        return result;
    }

    @Override public String toString() {
        return "(" + mid + ',' + uid + ')';
    }
}
