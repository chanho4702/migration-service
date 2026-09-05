package com.platform.migrationservice.migration.confluence.restriction;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.platform.proto.org.v1.LookupMembersRequest;
import com.platform.proto.org.v1.LookupMembersResponse;
import com.platform.proto.org.v1.LookupTeamsRequest;
import com.platform.proto.org.v1.LookupTeamsResponse;
import com.platform.proto.org.v1.MemberMatch;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.org.v1.TeamMatch;
import com.platform.migrationservice.migration.worker.MigrationStageException;
import com.platform.migrationservice.permission.GrpcPermissionClient;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * org-service의 이름 조회로 원본 사람·그룹을 우리 계정·팀에 짝짓는다(common-proto 0.16.0).
 *
 * 두 가지가 이 클래스의 전부다.
 *
 * 1. **묶어서 묻는다.** {@link #warmUp}이 한 항목의 주체를 한 번에 실어 보낸다(요청 상한 200이라
 *    그 단위로 쪼갠다). 이후 개별 resolve 호출은 캐시만 본다 — 문서 하나에 열 명이 걸린 제한이
 *    열 번의 왕복이 되지 않게 한다.
 * 2. **실패를 구분한다.** org가 닿지 않으면(UNAVAILABLE·DEADLINE) **다시 시도할 수 있는 단계
 *    실패**로 올린다. 여기서 "못 찾았다"로 삼키면 잡이 성공으로 끝나면서 문서 전부가 잡 요청자
 *    소유에 요청자 단독 제한으로 잠긴다 — 되돌리려면 사람이 페이지마다 손봐야 한다.
 *    그 밖의 오류(구버전 org의 UNIMPLEMENTED 포함)는 미매핑으로 두고 호출부의 fail-closed에 맡긴다.
 *
 * 캐시는 짧게 잡는다. 이관은 같은 작성자를 수백 번 다시 묻지만, org에 계정이 새로 생겼다면 그
 * 잡이 끝나기 전에 반영되는 편이 낫다.
 */
@Slf4j
public class GrpcMigrationPrincipalResolver implements MigrationPrincipalResolver {

    /** org 조회 자체가 불가능했다 — 항목을 데드레터로 보내지 않고 재시도 대상으로 둔다. */
    public static final String ORG_LOOKUP_UNAVAILABLE = "ORG_LOOKUP_UNAVAILABLE";

    /** 한 요청의 항목 상한(org proto 계약). 넘기면 org가 INVALID_ARGUMENT로 막는다. */
    private static final int BATCH_LIMIT = 200;

    private final PermissionServiceGrpc.PermissionServiceBlockingStub stub;
    private final Cache<String, Optional<Long>> users = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(20_000)
            .build();
    private final Cache<String, Optional<Long>> teams = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(5_000)
            .build();

    public GrpcMigrationPrincipalResolver(PermissionServiceGrpc.PermissionServiceBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    public void warmUp(Collection<SourceUser> sourceUsers, Collection<String> groupNames) {
        lookupUsers(sourceUsers == null ? List.of() : sourceUsers);
        lookupTeams(groupNames == null ? List.of() : groupNames);
    }

    @Override
    public Optional<Long> resolveUser(SourceUser user) {
        String key = userKey(user);
        if (key == null) {
            return Optional.empty();
        }
        Optional<Long> cached = users.getIfPresent(key);
        if (cached == null) {
            lookupUsers(List.of(user));
            cached = users.getIfPresent(key);
        }
        return cached == null ? Optional.empty() : cached;
    }

    @Override
    public Optional<Long> resolveTeam(String groupName) {
        String key = normalize(groupName);
        if (key == null) {
            return Optional.empty();
        }
        Optional<Long> cached = teams.getIfPresent(key);
        if (cached == null) {
            lookupTeams(List.of(groupName));
            cached = teams.getIfPresent(key);
        }
        return cached == null ? Optional.empty() : cached;
    }

    /**
     * 아직 모르는 사람만 골라 org에 묻는다. 응답에 없는 질의는 "없음"으로 캐시한다 — 그러지 않으면
     * 대조 안 되는 작성자 한 명 때문에 문서마다 같은 조회가 다시 나간다.
     */
    private void lookupUsers(Collection<SourceUser> sourceUsers) {
        Map<String, Query> pending = new LinkedHashMap<>();
        for (SourceUser user : sourceUsers) {
            String key = userKey(user);
            if (key == null || users.getIfPresent(key) != null || pending.containsKey(key)) {
                continue;
            }
            String email = trimmed(user.email());
            pending.put(key, email == null
                    ? new Query(null, trimmed(user.username()))
                    : new Query(email, null));
        }
        if (pending.isEmpty()) {
            return;
        }
        for (List<Map.Entry<String, Query>> chunk : chunks(new ArrayList<>(pending.entrySet()))) {
            LookupMembersRequest.Builder request = LookupMembersRequest.newBuilder();
            for (Map.Entry<String, Query> entry : chunk) {
                Query query = entry.getValue();
                if (query.email() != null) {
                    request.addEmails(query.email());
                } else {
                    request.addUsernames(query.username());
                }
            }
            Map<String, Long> matched = new LinkedHashMap<>();
            try {
                LookupMembersResponse response = stub.lookupMembers(request.build());
                for (MemberMatch match : response.getMatchesList()) {
                    String key = normalize(match.getQuery());
                    if (key != null && match.getMemberId() > 0) {
                        matched.put(key, match.getMemberId());
                    }
                }
            } catch (Exception exception) {
                fallback(exception, "사용자");
                return;
            }
            for (Map.Entry<String, Query> entry : chunk) {
                Query query = entry.getValue();
                String answerKey = normalize(query.email() != null ? query.email() : query.username());
                users.put(entry.getKey(), Optional.ofNullable(matched.get(answerKey)));
            }
        }
    }

    private void lookupTeams(Collection<String> groupNames) {
        Set<String> pending = new LinkedHashSet<>();
        for (String name : groupNames) {
            String key = normalize(name);
            if (key != null && teams.getIfPresent(key) == null) {
                pending.add(key);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        for (List<String> chunk : chunks(new ArrayList<>(pending))) {
            Map<String, Long> matched = new LinkedHashMap<>();
            try {
                LookupTeamsResponse response = stub.lookupTeams(
                        LookupTeamsRequest.newBuilder().addAllNames(chunk).build());
                for (TeamMatch match : response.getMatchesList()) {
                    String key = normalize(match.getQuery());
                    if (key != null && match.getTeamId() > 0) {
                        matched.put(key, match.getTeamId());
                    }
                }
            } catch (Exception exception) {
                fallback(exception, "팀");
                return;
            }
            for (String key : chunk) {
                teams.put(key, Optional.ofNullable(matched.get(key)));
            }
        }
    }

    /**
     * 조회 실패의 갈림길. org 자체가 닿지 않으면 재시도 가능한 단계 실패로 올리고, 그 밖의
     * 오류는 미매핑으로 둔다 — 캐시에 넣지 않아 다음 항목이 다시 시도한다.
     */
    private static void fallback(Exception exception, String what) {
        if (GrpcPermissionClient.isUnavailable(exception)) {
            log.error("org {} 조회 불가 — 항목을 재시도로 넘긴다", what, exception);
            throw MigrationStageException.retryable(ORG_LOOKUP_UNAVAILABLE);
        }
        log.warn("org {} 조회 실패 — 미매핑으로 둔다(fail-closed)", what, exception);
    }

    /** 이메일이 있으면 이메일이 키다. 없으면 username을 쓰되 이메일 키와 섞이지 않게 접두어를 둔다. */
    private static String userKey(SourceUser user) {
        if (user == null) {
            return null;
        }
        String email = normalize(user.email());
        if (email != null) {
            return email;
        }
        String username = normalize(user.username());
        return username == null ? null : "username:" + username;
    }

    private static String normalize(String value) {
        String trimmed = trimmed(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static <T> List<List<T>> chunks(List<T> all) {
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < all.size(); from += BATCH_LIMIT) {
            chunks.add(all.subList(from, Math.min(from + BATCH_LIMIT, all.size())));
        }
        return chunks;
    }

    /** org에 실어 보낼 질의 하나. 이메일과 username 중 하나만 채워진다. */
    private record Query(String email, String username) {
    }
}
