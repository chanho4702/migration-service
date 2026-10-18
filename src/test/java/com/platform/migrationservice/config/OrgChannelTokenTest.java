package com.platform.migrationservice.config;

import com.platform.common.grpc.InternalTokenClientInterceptor;
import com.platform.common.grpc.InternalTokenServerInterceptor;
import com.platform.proto.org.v1.CheckPermissionRequest;
import com.platform.proto.org.v1.CheckPermissionResponse;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 서비스 간 gRPC 토큰(P-01 단계 2) — 실제 빈 메서드로 만든 org 채널이 토큰을 메타데이터에 싣는가 */
class OrgChannelTokenTest {

    private final AtomicReference<String> seen = new AtomicReference<>("<호출 안 됨>");
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void startOrg() throws IOException {
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                               ServerCallHandler<Q, R> next) {
                seen.set(headers.get(InternalTokenServerInterceptor.HEADER));
                return next.startCall(call, headers);
            }
        };
        PermissionServiceGrpc.PermissionServiceImplBase org = new PermissionServiceGrpc.PermissionServiceImplBase() {
            @Override
            public void checkPermission(CheckPermissionRequest request, StreamObserver<CheckPermissionResponse> o) {
                o.onNext(CheckPermissionResponse.newBuilder().setAllowed(true).build());
                o.onCompleted();
            }
        };
        server = ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(org, capture)).build().start();
    }

    @AfterEach
    void teardown() throws InterruptedException {
        if (channel != null) channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }

    private void call(String token) {
        channel = new SecurityConfig().orgChannel("127.0.0.1", server.getPort(), new InternalTokenClientInterceptor(token));
        PermissionServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                .checkPermission(CheckPermissionRequest.newBuilder().build());
    }

    @Test
    void 설정된_토큰을_trim해서_싣는다() {
        call("  s3cret  ");
        assertThat(seen.get()).isEqualTo("s3cret");
    }

    @Test
    void 토큰이_비면_헤더를_싣지_않는다() {
        call("");
        assertThat(seen.get()).isNull();
    }
}
