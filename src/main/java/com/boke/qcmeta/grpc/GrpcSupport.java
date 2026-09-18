package com.boke.qcmeta.grpc;

import com.boke.qcmeta.api.ApiException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.function.Supplier;

final class GrpcSupport {
    private GrpcSupport() {
    }

    static <T> void respond(StreamObserver<T> observer, Supplier<T> action) {
        try {
            observer.onNext(action.get());
            observer.onCompleted();
        } catch (ApiException exception) {
            Status status = switch (exception.status().value()) {
                case 404 -> Status.NOT_FOUND;
                case 409 -> Status.ALREADY_EXISTS;
                case 401 -> Status.UNAUTHENTICATED;
                case 403 -> Status.PERMISSION_DENIED;
                case 429 -> Status.RESOURCE_EXHAUSTED;
                case 502,503,504 -> Status.UNAVAILABLE;
                default -> Status.INVALID_ARGUMENT;
            };
            observer.onError(status.withDescription(exception.getMessage()).asRuntimeException());
        } catch (org.springframework.dao.DataAccessException | com.boke.qcmeta.ycloud.YCloudClient.YCloudException exception) {
            observer.onError(Status.UNAVAILABLE.withDescription("依赖暂时不可用，请保留原请求编号稍后核对").asRuntimeException());
        } catch (RuntimeException exception) {
            observer.onError(Status.INTERNAL.withDescription("服务内部处理失败")
                    .asRuntimeException());
        }
    }
}
