package com.platform.migrationservice.migration.confluence.dc;

import com.platform.migrationservice.migration.MigrationItemIntake;
import com.platform.migrationservice.migration.dto.MigrationDiscoverResponse;
import com.platform.migrationservice.migration.dto.MigrationItemEnqueueRequest;
import com.platform.migrationservice.migration.model.MigrationItem;
import com.platform.migrationservice.migration.model.MigrationSource;
import com.platform.migrationservice.migration.repository.MigrationItemRepository;
import com.platform.migrationservice.migration.repository.MigrationSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * 원본 스페이스의 페이지와 블로그 글을 훑어 job의 처리 대기열에 담는다.
 *
 * 담는 순서가 중요하다. RESOLVE에서 부모의 대상 페이지 id를 object map에서 찾는데, 부모가 아직
 * 안 만들어졌으면 문서가 루트로 떨어진다(WARNING PARENT_NOT_FOUND). 그래서 조상 깊이 오름차순으로
 * 담고, worker는 item id 순으로 집으므로 부모가 항상 먼저 처리된다.
 *
 * 같은 깊이 안의 순서는 목록 API가 알려주지 않는다. 그래서 부모마다 `child/page`를 한 번 더 불러
 * **원본이 정한 형제 순서**를 받아 item에 적어 둔다(M2). 이 호출이 실패하는 사이트에서는 순서를
 * 모르는 채로 두고 M1 규칙(id 오름차순)으로 되돌아간다 — 정렬 하나 때문에 이관 전체를 세우지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfluenceDcDiscoveryService {

    private final ConfluenceDcClient client;
    private final ConfluenceDcProperties properties;
    private final MigrationSourceRepository sources;
    private final MigrationItemRepository items;
    private final MigrationItemIntake intake;

    /**
     * 이 job의 원본을 다시 훑어 새 항목만 담는다. 이미 담긴 항목은 건드리지 않으므로 몇 번을
     * 눌러도 결과가 같다(멱등).
     */
    public MigrationDiscoverResponse discover(long jobId, Instant now) {
        MigrationSource source = sources.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("이 작업에는 원본 정보가 없습니다"));
        ConfluenceDcCredentials credentials = new ConfluenceDcCredentials(
                source.getBaseUrl(), source.getSpaceKey(), source.getAuthToken());

        List<ConfluenceContentSummary> discovered = fetchAll(credentials);
        Map<String, Integer> siblingOrders = fetchSiblingOrders(credentials, discovered);
        discovered.sort(Comparator.comparingInt(ConfluenceContentSummary::depth)
                .thenComparingInt(page -> siblingOrders.getOrDefault(page.id(), Integer.MAX_VALUE))
                .thenComparing(idOrder()));

        int enqueued = 0;
        int skipped = 0;
        for (ConfluenceContentSummary page : discovered) {
            if (enqueue(jobId, page, siblingOrders.get(page.id()))) {
                enqueued++;
            } else {
                skipped++;
            }
        }
        String spaceName = client.probe(credentials).spaceName();
        recordDiscovery(jobId, discovered.size(), spaceName, now);
        log.info("컨플루언스 DC 발견 완료: job={} 발견={} 신규={} 기존={}",
                jobId, discovered.size(), enqueued, skipped);
        return new MigrationDiscoverResponse(discovered.size(), enqueued, skipped);
    }

    /**
     * 페이지와 블로그 글을 모두 담는다(M3 §5.1). 블로그를 뒤에 붙이는 이유는 상한에 닿았을 때
     * 트리부터 지키기 위해서다 — 부모를 못 담으면 그 아래 문서가 전부 루트로 떨어진다.
     */
    private List<ConfluenceContentSummary> fetchAll(ConfluenceDcCredentials credentials) {
        List<ConfluenceContentSummary> all = new ArrayList<>();
        if (!collect(all, start -> client.listPages(credentials, start))) {
            return all;
        }
        collect(all, start -> client.listBlogPosts(credentials, start));
        return all;
    }

    /** @return 상한에 닿지 않았으면 true(다음 종류를 더 담아도 된다) */
    private boolean collect(List<ConfluenceContentSummary> all,
                            IntFunction<ConfluenceContentPage> fetch) {
        int start = 0;
        while (true) {
            ConfluenceContentPage page = fetch.apply(start);
            all.addAll(page.results());
            if (all.size() >= properties.maxPages()) {
                // 상한에서 자른다. 소리 없이 절반만 옮기는 것보다, 상한에 닿았다는 사실이 로그에
                // 남고 관리자가 상한을 올리거나 스페이스를 쪼개는 편이 낫다.
                log.warn("원본 문서가 상한({})을 넘어 잘랐다 — platform.migration.dc.max-pages",
                        properties.maxPages());
                List<ConfluenceContentSummary> capped =
                        new ArrayList<>(all.subList(0, properties.maxPages()));
                all.clear();
                all.addAll(capped);
                return false;
            }
            if (!page.hasMore() || page.results().isEmpty()) {
                return true;
            }
            start += page.results().size();
        }
    }

    /**
     * 부모별 형제 순서를 모은다. 발견된 페이지의 부모만 부르므로 호출 수는 "자식이 있는 부모 수 + 1"이다.
     *
     * 실패는 삼킨다 — `child/page`가 없거나 막힌 사이트에서도 이관 자체는 되어야 한다. 그때 순서는
     * 비고, 호출부가 M1 규칙으로 되돌아간다.
     */
    private Map<String, Integer> fetchSiblingOrders(ConfluenceDcCredentials credentials,
                                                    List<ConfluenceContentSummary> discovered) {
        Set<String> parents = new LinkedHashSet<>();
        for (ConfluenceContentSummary page : discovered) {
            if (!page.ancestors().isEmpty()) {
                parents.add(page.ancestors().get(page.ancestors().size() - 1));
            }
        }
        Map<String, Integer> orders = new HashMap<>();
        collectOrder(orders, () -> fetchAllPages(start -> client.listRootPages(credentials, start)), "root");
        for (String parent : parents) {
            collectOrder(orders, () -> fetchAllPages(start -> client.listChildPages(credentials, parent, start)),
                    parent);
        }
        return orders;
    }

    private void collectOrder(Map<String, Integer> orders,
                              Supplier<List<ConfluenceContentSummary>> fetch, String parentLabel) {
        List<ConfluenceContentSummary> siblings;
        try {
            siblings = fetch.get();
        } catch (RuntimeException exception) {
            log.warn("원본 형제 순서를 읽지 못했다 — 발견 순서로 대신한다: parent={}", parentLabel);
            return;
        }
        for (int index = 0; index < siblings.size(); index++) {
            orders.put(siblings.get(index).id(), index);
        }
    }

    private List<ConfluenceContentSummary> fetchAllPages(IntFunction<ConfluenceContentPage> fetch) {
        List<ConfluenceContentSummary> all = new ArrayList<>();
        int start = 0;
        while (all.size() < properties.maxPages()) {
            ConfluenceContentPage page = fetch.apply(start);
            all.addAll(page.results());
            if (!page.hasMore() || page.results().isEmpty()) {
                break;
            }
            start += page.results().size();
        }
        return all;
    }

    /** 새로 담았으면 true, 이미 있어 건너뛰었으면 false(그때도 형제 순서는 갱신한다). */
    private boolean enqueue(long jobId, ConfluenceContentSummary page, Integer siblingOrder) {
        String sourceKey = MigrationItem.sourceKeyFor(page.id());
        MigrationItem existing = items.findByJobIdAndSourceKey(jobId, sourceKey).orElse(null);
        if (existing != null) {
            intake.updateSiblingOrder(existing.getId(), siblingOrder);
            return false;
        }
        MigrationItemEnqueueRequest request = new MigrationItemEnqueueRequest(
                page.id(), String.valueOf(page.version()), checksumOf(page), "dc:content/" + page.id(),
                siblingOrder);
        try {
            intake.insert(jobId, request);
            return true;
        } catch (DataIntegrityViolationException e) {
            // 같은 job에 두 발견이 겹쳤다. unique 제약이 이겼으니 이쪽은 건너뛴 것으로 센다.
            return false;
        }
    }

    /**
     * 발견 기록은 순회가 끝난 뒤 한 번만 쓴다 — 원본을 훑는 내내 행을 잠그고 있으면 상태 조회가
     * 그 시간만큼 막힌다. 리포지토리 자체 트랜잭션(merge)으로 충분하다.
     */
    private void recordDiscovery(long jobId, int discoveredCount, String spaceName, Instant now) {
        sources.findById(jobId).ifPresent(source -> {
            source.recordDiscovery(discoveredCount, spaceName, now);
            sources.save(source);
        });
    }

    /** enqueue 때의 원본 상태 지문. 버전이 오르면 값이 달라져 재이관이 갱신으로 이어진다. */
    static String checksumOf(ConfluenceContentSummary page) {
        return sha256(page.id() + ":" + page.version());
    }

    /** id는 숫자 문자열이지만 사이트에 따라 아닐 수 있어, 숫자면 숫자로 아니면 사전순으로 센다. */
    private static Comparator<ConfluenceContentSummary> idOrder() {
        return Comparator.comparing(summary -> {
            try {
                return String.format("%020d", Long.parseLong(summary.id()));
            } catch (NumberFormatException exception) {
                return summary.id();
            }
        });
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
