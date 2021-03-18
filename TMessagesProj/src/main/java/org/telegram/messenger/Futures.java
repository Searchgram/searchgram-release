package org.telegram.messenger;

import java.util.concurrent.CompletableFuture;

import org.telegram.messenger.infra.AndroidFuture;

import androidx.annotation.NonNull;

public class Futures {
    public static <T> AndroidFuture<T> queueFuture(DispatchQueue queue) {
        return new AndroidFuture<T>() {
            @Override
            public boolean complete(T value) {
                queue.postRunnable(() -> super.complete(value));
                return true;
            }

            @Override
            public boolean completeExceptionally(Throwable ex) {
                queue.postRunnable(() -> super.completeExceptionally(ex));
                return true;
            }
        };
    }

    public static class UIThreadFuture<T> extends AndroidFuture<T> {
        @NonNull
        public static <U> UIThreadFuture<U> completedFuture(U value) {
            UIThreadFuture<U> future = new UIThreadFuture<>();
            future.complete(value);
            return future;
        }

        @Override
        public boolean complete(T value) {
            AndroidUtilities.runOnUIThread(() -> super.complete(value));
            return true;
        }

        @Override
        public boolean completeExceptionally(Throwable ex) {
            AndroidUtilities.runOnUIThread(() -> super.completeExceptionally(ex));
            return true;
        }
    }
}
