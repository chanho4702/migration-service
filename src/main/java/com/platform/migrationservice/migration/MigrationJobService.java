package com.platform.migrationservice.migration;

import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.common.error.ServiceUnavailableException;
import com.platform.migrationservice.migration.confluence.dc.ConfluenceDcClient;
import com.platform.migrationservice.migration.confluence.dc.ConfluenceDcCodes;
import com.platform.migrationservice.migration.confluence.dc.ConfluenceDcCredentials;
import com.platform.migrationservice.migration.confluence.dc.ConfluenceDcDiscoveryService;
import com.platform.migrationservice.migration.confluence.dc.ConfluenceSpaceProbe;
import com.platform.migrationservice.migration.dto.ConfluenceDcProbeRequest;
import com.platform.migrationservice.migration.dto.ConfluenceDcProbeResponse;
import com.platform.migrationservice.migration.dto.MigrationDiscoverResponse;
import com.platform.migrationservice.migration.dto.MigrationItemEnqueueRequest;
import com.platform.migrationservice.migration.dto.MigrationItemPageResponse;
import com.platform.migrationservice.migration.dto.MigrationItemResponse;
import com.platform.migrationservice.migration.dto.MigrationJobCounts;
import com.platform.migrationservice.migration.dto.MigrationJobCreateRequest;
import com.platform.migrationservice.migration.dto.MigrationJobDetailResponse;
import com.platform.migrationservice.migration.dto.MigrationJobSummary;
import com.platform.migrationservice.migration.dto.MigrationSourceSummary;
import com.platform.migrationservice.migration.model.MigrationItem;
import com.platform.migrationservice.migration.model.MigrationItemStatus;
import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationJobStatus;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.model.MigrationSource;
import com.platform.migrationservice.migration.model.MigrationStage;
import com.platform.migrationservice.migration.repository.MigrationSourceRepository;
import com.platform.migrationservice.migration.worker.MigrationStageException;
import com.platform.migrationservice.migration.report.MigrationDeadLetterResponse;
import com.platform.migrationservice.migration.report.MigrationIssueSummary;
import com.platform.migrationservice.migration.report.MigrationJobResponse;
import com.platform.migrationservice.migration.report.MigrationReportResponse;
import com.platform.migrationservice.migration.report.MigrationStageCount;
import com.platform.migrationservice.migration.report.MigrationStatusCount;
import com.platform.migrationservice.migration.repository.MigrationIssueRepository;
import com.platform.migrationservice.migration.repository.MigrationItemRepository;
import com.platform.migrationservice.migration.repository.MigrationJobRepository;
import com.platform.migrationservice.permission.PermissionClient;
import com.platform.migrationservice.permission.SpaceAction;
import com.platform.migrationservice.wiki.WikiImportApi;
import com.platform.migrationservice.wiki.WikiImportCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * migration job의 수명주기와 보고서. 대상 스페이스 ADMIN만 job을 만들고 읽을 수 있다 —
 * 보고서에는 아직 옮기지 않은 외부 객체 ID가 들어가므로 VIEW 권한으로는 열지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MigrationJobService {

    /** 발견을 건너뛰고 시작을 눌렀을 때의 안내. 프론트는 이 문장을 그대로 보여준다. */
    public static final String MIGRATION_NOTHING_DISCOVERED =
            "옮길 항목이 없습니다 — 먼저 원본 발견을 실행하세요";

    /** 관리 화면 목록 상한 — 최신 것부터 이만큼만 본다. */
    public static final int JOB_LIST_SIZE = 50;

    /** 실패 항목 표의 한 페이지 크기. */
    public static final int ITEM_PAGE_SIZE = 50;

    private final MigrationJobRepository jobs;
    private final MigrationItemRepository items;
    private final MigrationIssueRepository issues;
    private final MigrationSourceRepository sources;
    private final MigrationItemIntake intake;
    private final WikiImportApi wiki;
    private final PermissionClient permissions;
    private final ConfluenceDcClient dcClient;
    private final ConfluenceDcDiscoveryService discovery;

    /**
     * 원본에 붙을 수 있는지 확인한다. 여기서만 stage 코드를 HTTP 상태로 바꾼다 — worker 밖에서
     * 부르는 유일한 DC 호출이고, 관리자는 "왜 안 되는지"를 화면에서 바로 봐야 한다.
     */
    @Transactional(readOnly = true)
    public ConfluenceDcProbeResponse probeConfluenceDc(long userId, ConfluenceDcProbeRequest req) {
        requireGlobalAdmin(userId);
        ConfluenceDcCredentials credentials =
                new ConfluenceDcCredentials(req.baseUrl(), req.spaceKey(), req.token());
        try {
            ConfluenceSpaceProbe probe = dcClient.probe(credentials);
            if (probe.spaceName() == null || probe.spaceName().isBlank()) {
                throw new NotFoundException("원본 스페이스를 찾을 수 없습니다: " + req.spaceKey());
            }
            return new ConfluenceDcProbeResponse(probe.spaceName(), probe.homepageId(), probe.pageCount());
        } catch (MigrationStageException e) {
            throw toApiFailure(e, req.spaceKey());
        }
    }

    public MigrationJobResponse create(long userId, MigrationJobCreateRequest req) {
        requireTargetSpace(userId, req.targetSpaceId());
        requireAdmin(userId, req.targetSpaceId());
        ConfluenceDcCredentials credentials = req.source() == null ? null
                : new ConfluenceDcCredentials(req.source().baseUrl(), req.source().spaceKey(),
                        req.source().token());
        if (req.provider() == MigrationProvider.CONFLUENCE_DC && credentials == null) {
            throw new IllegalArgumentException("원본 컨플루언스 접속 정보가 필요합니다");
        }
        // 원본 주소의 호스트가 곧 인스턴스 식별자다 — 관리자에게 같은 값을 두 번 묻지 않는다.
        String instanceId = req.sourceInstanceId() == null || req.sourceInstanceId().isBlank()
                ? (credentials == null ? null : credentials.instanceId())
                : req.sourceInstanceId();
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("원본 인스턴스 식별자가 필요합니다");
        }
        MigrationJob job = jobs.save(MigrationJob.create(req.provider(), instanceId,
                req.targetSpaceId(), userId, req.mode()));
        if (credentials != null) {
            sources.save(MigrationSource.of(job.getId(), credentials.baseUrl(),
                    credentials.spaceKey(), credentials.token()));
        }
        return MigrationJobResponse.from(job, 0);
    }

    /** 원본 트리를 훑어 처리 대기열을 채운다. 이미 담긴 항목은 건너뛰므로 다시 눌러도 안전하다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MigrationDiscoverResponse discover(long userId, long jobId, Instant now) {
        MigrationJob job = requireJob(userId, jobId);
        if (job.getProvider() != MigrationProvider.CONFLUENCE_DC) {
            throw new ConflictException("이 작업은 컨플루언스 DC 원본이 아닙니다");
        }
        if (job.getStatus() != MigrationJobStatus.PENDING) {
            throw new ConflictException("이미 시작된 job에는 원본을 추가할 수 없습니다: " + job.getStatus());
        }
        try {
            return discovery.discover(jobId, now);
        } catch (MigrationStageException e) {
            throw toApiFailure(e, null);
        }
    }

    /** 관리자 목록 — 최신순. 원본 토큰은 실리지 않는다. */
    @Transactional(readOnly = true)
    public List<MigrationJobSummary> list(long userId) {
        requireGlobalAdmin(userId);
        return jobs.findAllByOrderByIdDesc(Limit.of(JOB_LIST_SIZE)).stream()
                .map(job -> MigrationJobSummary.from(job, sources.findById(job.getId()).orElse(null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public MigrationItemPageResponse listItems(long userId, long jobId, MigrationItemStatus status,
                                               MigrationStage stage, int page) {
        requireJob(userId, jobId);
        int pageNumber = Math.max(page, 0);
        org.springframework.data.domain.Page<MigrationItem> found = items.findFiltered(jobId, status, stage,
                PageRequest.of(pageNumber, ITEM_PAGE_SIZE));
        return new MigrationItemPageResponse(
                found.getContent().stream().map(MigrationItemResponse::from).toList(),
                pageNumber, ITEM_PAGE_SIZE, found.getTotalElements());
    }

    @Transactional(readOnly = true)
    public MigrationJobDetailResponse detail(long userId, long jobId) {
        MigrationJob job = requireJob(userId, jobId);
        return MigrationJobDetailResponse.of(
                MigrationJobResponse.from(job, items.countByJobId(jobId)),
                sources.findById(jobId).map(MigrationSourceSummary::from).orElse(null),
                new MigrationJobCounts(statusCounts(jobId), stageCounts(jobId)));
    }

    /**
     * 원본 객체를 job에 등록한다. 같은 외부 객체를 다시 넣어도 새 item을 만들지 않는다 —
     * extractor가 중단 후 재개해도 job이 부풀지 않아야 한다.
     *
     * 트랜잭션을 열지 않는 이유: 두 재시도가 동시에 들어오면 둘 다 조회에서 못 찾고 같은
     * `(job_id, source_key)`를 넣으려 한다. 하나는 unique 제약에 걸리는데, 그 예외가 열린
     * 트랜잭션을 rollback-only로 만들면 같은 트랜잭션에서 다시 읽어 멱등 응답을 줄 수 없다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MigrationItemResponse enqueue(long userId, long jobId, MigrationItemEnqueueRequest req) {
        MigrationJob job = requireJob(userId, jobId);
        if (job.getStatus() != MigrationJobStatus.PENDING) {
            throw new ConflictException("이미 시작된 job에는 원본을 추가할 수 없습니다: " + job.getStatus());
        }
        String sourceKey = MigrationItem.sourceKeyFor(req.externalObjectId());
        Optional<MigrationItem> existing = items.findByJobIdAndSourceKey(jobId, sourceKey);
        if (existing.isPresent()) {
            return MigrationItemResponse.from(existing.get());
        }
        try {
            return MigrationItemResponse.from(intake.insert(jobId, req));
        } catch (DataIntegrityViolationException e) {
            return items.findByJobIdAndSourceKey(jobId, sourceKey)
                    .map(MigrationItemResponse::from)
                    .orElseThrow(() -> e);
        }
    }

    /** 등록을 마감하고 worker가 집을 수 있게 한다. 이후 enqueue는 409다. */
    public MigrationJobResponse start(long userId, long jobId, Instant now) {
        MigrationJob job = lockJob(userId, jobId);
        if (job.getStatus() != MigrationJobStatus.PENDING) {
            throw new ConflictException("이미 시작된 job입니다: " + job.getStatus());
        }
        long itemCount = items.countByJobId(jobId);
        if (itemCount == 0) {
            // 항목 0으로 시작하면 job이 즉시 COMPLETED로 마감돼 "성공적으로 아무것도 안 옮겼다"가 된다.
            // 발견을 건너뛴 실수를 성공으로 보고하는 것이 최악이라 여기서 막는다.
            throw new IllegalArgumentException(MIGRATION_NOTHING_DISCOVERED);
        }
        job.start(now);
        return MigrationJobResponse.from(job, itemCount);
    }

    public MigrationJobResponse cancel(long userId, long jobId, Instant now) {
        MigrationJob job = lockJob(userId, jobId);
        if (job.getStatus() != MigrationJobStatus.PENDING && job.getStatus() != MigrationJobStatus.RUNNING) {
            throw new ConflictException("이미 종료된 job입니다: " + job.getStatus());
        }
        job.cancel(now);
        return MigrationJobResponse.from(job, items.countByJobId(jobId));
    }

    @Transactional(readOnly = true)
    public MigrationJobResponse get(long userId, long jobId) {
        MigrationJob job = requireJob(userId, jobId);
        return MigrationJobResponse.from(job, items.countByJobId(jobId));
    }

    @Transactional(readOnly = true)
    public MigrationReportResponse report(long userId, long jobId) {
        MigrationJob job = requireJob(userId, jobId);
        Map<String, Long> byStatus = statusCounts(jobId);
        Map<String, Long> byStage = stageCounts(jobId);
        // severity는 문자열 컬럼이라 DB 정렬이 심각도 순이 아니다. 심각한 것부터 보이게 여기서 정렬한다.
        List<MigrationIssueSummary> summaries = issues.summarize(jobId).stream()
                .sorted(Comparator.comparing((MigrationIssueSummary s) -> s.severity().ordinal()).reversed()
                        .thenComparing(MigrationIssueSummary::code))
                .toList();
        List<MigrationDeadLetterResponse> deadLetters = items
                .findByJobIdAndStatusOrderByIdAsc(jobId, MigrationItemStatus.DEAD_LETTER).stream()
                .map(MigrationDeadLetterResponse::from)
                .toList();
        return new MigrationReportResponse(MigrationJobResponse.from(job, items.countByJobId(jobId)),
                byStatus, byStage, summaries, deadLetters);
    }

    private MigrationJob requireJob(long userId, long jobId) {
        MigrationJob job = jobs.findById(jobId)
                .orElseThrow(() -> new NotFoundException("마이그레이션 작업을 찾을 수 없습니다: " + jobId));
        requireAdmin(userId, job.getTargetSpaceId());
        return job;
    }

    /**
     * 상태를 바꾸기 전에 행을 잠근다. 동시 `start`/`cancel`이 같은 상태를 읽고 둘 다 통과하면
     * 뒤늦은 쪽이 커밋 시점의 낙관적 락 실패로 500을 내는데, 사용자에게는 상태 충돌(409)이어야 한다.
     */
    private MigrationJob lockJob(long userId, long jobId) {
        MigrationJob job = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> new NotFoundException("마이그레이션 작업을 찾을 수 없습니다: " + jobId));
        requireAdmin(userId, job.getTargetSpaceId());
        return job;
    }

    /**
     * 대상 스페이스가 위키에 있는가. 우리 DB에는 스페이스가 없으므로 위키에 묻는다.
     *
     * 권한 판정보다 먼저 하는 이유: 없는 스페이스에 403을 주면 "권한이 없다"로 읽혀 관리자가
     * 엉뚱한 곳을 본다. 위키가 닿지 않으면 "없다"가 아니라 **503**이다 — 여기서 404로 뭉개면
     * 위키가 잠깐 죽은 사이에 만든 잡이 스페이스를 잘못 짚은 것처럼 보인다.
     */
    private void requireTargetSpace(long userId, Long spaceId) {
        if (spaceId == null) {
            throw new NotFoundException("스페이스를 찾을 수 없습니다: " + spaceId);
        }
        try {
            if (wiki.getSpace(userId, spaceId).isEmpty()) {
                throw new NotFoundException("스페이스를 찾을 수 없습니다: " + spaceId);
            }
        } catch (MigrationStageException e) {
            if (WikiImportCodes.UNAVAILABLE.equals(e.getCode())) {
                throw new ServiceUnavailableException("위키에 연결할 수 없습니다");
            }
            if (WikiImportCodes.NOT_FOUND.equals(e.getCode())) {
                throw new NotFoundException("스페이스를 찾을 수 없습니다: " + spaceId);
            }
            throw new ServiceUnavailableException("위키가 요청을 거부했습니다");
        }
    }

    private void requireAdmin(long userId, Long spaceId) {
        if (spaceId == null || !permissions.isAllowed(userId, spaceId, SpaceAction.ADMIN)) {
            throw new ForbiddenException("마이그레이션 권한이 없습니다");
        }
    }

    /**
     * 전역 관리자(GLOBAL grant) 판정 — 스페이스 삭제 기록(V30)과 같은 방식이다. 연결 확인과 잡
     * 목록은 대상 스페이스가 아직 없거나 여러 스페이스에 걸치므로 스페이스 ADMIN으로는 판정할 수 없다.
     */
    private void requireGlobalAdmin(long userId) {
        if (!permissions.accessibleSpaces(userId).all()) {
            throw new ForbiddenException("마이그레이션 관리는 전역 관리자만 할 수 있습니다");
        }
    }

    private Map<String, Long> statusCounts(long jobId) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (MigrationStatusCount count : items.countByStatus(jobId)) {
            byStatus.put(count.status().name(), count.total());
        }
        return byStatus;
    }

    private Map<String, Long> stageCounts(long jobId) {
        Map<String, Long> byStage = new LinkedHashMap<>();
        for (MigrationStageCount count : items.countByStage(jobId)) {
            byStage.put(count.stage().name(), count.total());
        }
        return byStage;
    }

    /**
     * stage 코드를 사용자에게 보일 오류로 옮긴다. 가용성 장애만 503이고 나머지는 요청 문제다 —
     * gRPC 권한 판정에서 쓰는 것과 같은 원칙(코드로 분기, 뭉뚱그리지 않는다).
     */
    private RuntimeException toApiFailure(MigrationStageException e, String spaceKey) {
        return switch (e.getCode()) {
            case ConfluenceDcCodes.AUTH ->
                    new ForbiddenException("원본 컨플루언스 인증에 실패했습니다 — 토큰과 권한을 확인하세요");
            case ConfluenceDcCodes.NOT_FOUND -> new NotFoundException(
                    spaceKey == null ? "원본에서 대상을 찾을 수 없습니다"
                            : "원본 스페이스를 찾을 수 없습니다: " + spaceKey);
            case ConfluenceDcCodes.UNAVAILABLE ->
                    new ServiceUnavailableException("원본 컨플루언스에 연결할 수 없습니다");
            case ConfluenceDcCodes.REDIRECT_REFUSED ->
                    new IllegalArgumentException("원본 주소가 다른 곳으로 넘깁니다 — 최종 주소를 직접 입력하세요");
            default -> new IllegalArgumentException("원본 컨플루언스 응답을 이해할 수 없습니다");
        };
    }
}
