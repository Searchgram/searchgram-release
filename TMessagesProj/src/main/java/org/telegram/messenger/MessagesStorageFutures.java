package org.telegram.messenger;

import java.util.ArrayList;

import androidx.annotation.AnyThread;
import androidx.annotation.Nullable;
import org.telegram.messenger.Futures.UIThreadFuture;
import org.telegram.messenger.support.Tuple;

public final class MessagesStorageFutures {
    private final MessagesStorage storage;

    MessagesStorageFutures(MessagesStorage storage) {
        this.storage = storage;
    }

    @AnyThread
    public UIThreadFuture<ArrayList<MessageObject>> ftsSearch(String query, int minDate, int maxDate, Iterable<Long> usedIds, int limit) {
        UIThreadFuture<ArrayList<MessageObject>> future = new UIThreadFuture<>();

        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<MessageObject> results = new ArrayList<>();
            storage.ftsSearch(query, minDate, maxDate, limit, usedIds, results);
            future.complete(results);
        });

        return future;
    }

    @AnyThread
    public UIThreadFuture<Tuple<Integer, ArrayList<MessageObject>, Void>> ftsSearchInChat(String query, long dialogId, int minDate, int maxDate, int limit, @Nullable Iterable<Long> usedMids) {
        UIThreadFuture<Tuple<Integer, ArrayList<MessageObject>, Void>> future = new UIThreadFuture<>();

        storage.getStorageQueue().postRunnable(() -> {
            Tuple<Integer, ArrayList<MessageObject>, Void> results = Tuple.triple(0, new ArrayList<>(), null);
            storage.ftsSearchInChat(query, dialogId, minDate, maxDate, limit, usedMids, results);
            future.complete(results);
        });

        return future;
    }
}
