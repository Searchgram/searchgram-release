package org.telegram.tgnet;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.telegram.messenger.Futures;
import org.telegram.messenger.Futures.UIThreadFuture;
import org.telegram.tgnet.TLRPC.TL_error;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// XXX:
// 1. support timeout
// 2. support fail-safe
public class ConnectionsManagerFutures {
    private final ConnectionsManager manager;

    public ConnectionsManagerFutures(ConnectionsManager manager) {
        this.manager = manager;
    }

    /**
     * @param responseHandler runs on stageQueue
     * @param <T>
     * @return a CompletableFuture that can takes async actions on UI thread
     */
    public <R extends TLObject, T> RequestFuture<R, T> sendRequest(TLObject request, Function<R, T> responseHandler) {
        RequestFuture<R, T> future = new RequestFuture<>(0);
        future.reqId = manager.sendRequest(request, (response, error) -> {
            if (error == null) {
                T t = responseHandler.apply((R)response);
                future.complete(new ResponseObject<>((R)response, null, t));
            } else {
                future.complete(new ResponseObject<>((R)response, error, null));
            }
        });
        return future;
    }

    public static class RequestFuture<R extends TLObject, T> extends Futures.UIThreadFuture<ResponseObject<R, T>> {
        private int reqId;

        public RequestFuture(int reqId) {
            this.reqId = reqId;
        }

        public int getReqId() {
            return reqId;
        }

        @NonNull
        public static <R extends TLObject, T> RequestFuture<R, T> completedFuture(R res, T value) {
            RequestFuture<R, T> future = new RequestFuture<>(0);
            future.complete(new ResponseObject<>(res, null, value));
            return future;
        }
    }

    public static class ResponseObject<R extends TLObject, T> {
        public final R res;
        public final TLRPC.TL_error error;
        public final T data;

        public ResponseObject(R res, TL_error error, T data) {
            this.res = res;
            this.error = error;
            this.data = data;
        }
    }
}
