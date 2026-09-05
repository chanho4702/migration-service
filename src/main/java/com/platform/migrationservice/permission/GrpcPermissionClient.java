package com.platform.migrationservice.permission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.platform.common.error.ServiceUnavailableException;
import com.platform.proto.org.v1.Action;
import com.platform.proto.org.v1.CheckPermissionRequest;
import com.platform.proto.org.v1.ListUserGrantsRequest;
import com.platform.proto.org.v1.ListUserGrantsResponse;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.org.v1.ResourceType;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * org-service gRPC 권한 클라이언트(wiki-backend와 같은 규칙).
 *
 * 판정은 30초 Caffeine 캐시로 눌러 둔다 — 이관 화면은 같은 잡을 반복해서 폴링한다.
 * org가 닿지 않으면(UNAVAILABLE·DEADLINE) 503으로 전파하고, 그 밖의 실패는 fail-closed(false)다 —
 * 가용성보다 인가 안전이 먼저다. `denied_reason`은 읽지 않는다(화면에 이유를 싣지 않는다).
 */
@Slf4j
public class GrpcPermissionClient implements PermissionClient {

    private record CacheKey(long userId, long spaceId, SpaceAction action) {}

    private final PermissionServiceGrpc.PermissionServiceBlockingStub stub;
    private final Cache<CacheKey, Boolean> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    public GrpcPermissionClient(PermissionServiceGrpc.PermissionServiceBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    public boolean isAllowed(long userId, long spaceId, SpaceAction action) {
        return cache.get(new CacheKey(userId, spaceId, action), key -> {
            try {
                return stub.checkPermission(CheckPermissionRequest.newBuilder()
                        .setUserId(key.userId())
                        .setResourceType(ResourceType.SPACE)
                        .setResourceId(String.valueOf(key.spaceId()))
                        .setAction(toProto(key.action()))
                        .build()).getAllowed();
            } catch (Exception exception) {
                if (isUnavailable(exception)) {
                    log.error("권한 서비스 불가 — 503 전파: user={} space={} action={}",
                            key.userId(), key.spaceId(), key.action(), exception);
                    throw new ServiceUnavailableException("권한 서비스에 연결할 수 없습니다");
                }
                log.warn("권한조회 실패 — fail-closed: user={} space={} action={}",
                        key.userId(), key.spaceId(), key.action(), exception);
                return false;
            }
        });
    }

    @Override
    public AccessScope accessibleSpaces(long userId) {
        try {
            ListUserGrantsResponse response = stub.listUserGrants(
                    ListUserGrantsRequest.newBuilder().setUserId(userId).build());
            boolean global = response.getGrantsList().stream()
                    .anyMatch(grant -> grant.getResourceType() == ResourceType.GLOBAL);
            if (global) {
                return new AccessScope(true, Set.of());
            }
            Set<Long> ids = response.getGrantsList().stream()
                    .filter(grant -> grant.getResourceType() == ResourceType.SPACE)
                    .map(grant -> Long.parseLong(grant.getResourceId()))
                    .collect(Collectors.toSet());
            return new AccessScope(false, ids);
        } catch (Exception exception) {
            if (isUnavailable(exception)) {
                log.error("권한 서비스 불가 — 503 전파: user={}", userId, exception);
                throw new ServiceUnavailableException("권한 서비스에 연결할 수 없습니다");
            }
            log.warn("grant 목록 조회 실패 — fail-closed(빈 목록): user={}", userId, exception);
            return new AccessScope(false, Set.of());
        }
    }

    /**
     * gRPC 전송/가용성 장애(org-service 다운·타임아웃)만 판별 — 이 경우에만 503으로 전파한다.
     * public인 이유: 이관의 주체 대조도 같은 기준으로 "다시 시도할 수 있는 실패"를 가른다.
     */
    public static boolean isUnavailable(Throwable exception) {
        if (exception instanceof StatusRuntimeException status) {
            Status.Code code = status.getStatus().getCode();
            return code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
        }
        return false;
    }

    private static Action toProto(SpaceAction action) {
        return switch (action) {
            case VIEW -> Action.VIEW;
            case COMMENT -> Action.COMMENT;
            case EDIT -> Action.EDIT;
            case ADMIN -> Action.ADMIN;
        };
    }
}
