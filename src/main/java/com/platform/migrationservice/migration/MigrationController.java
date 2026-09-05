package com.platform.migrationservice.migration;

import com.platform.migrationservice.migration.dto.ConfluenceDcProbeRequest;
import com.platform.migrationservice.migration.dto.ConfluenceDcProbeResponse;
import com.platform.migrationservice.migration.dto.MigrationDiscoverResponse;
import com.platform.migrationservice.migration.dto.MigrationItemEnqueueRequest;
import com.platform.migrationservice.migration.dto.MigrationItemPageResponse;
import com.platform.migrationservice.migration.dto.MigrationItemResponse;
import com.platform.migrationservice.migration.dto.MigrationJobCreateRequest;
import com.platform.migrationservice.migration.dto.MigrationJobDetailResponse;
import com.platform.migrationservice.migration.dto.MigrationJobSummary;
import com.platform.migrationservice.migration.dto.MigrationLinkFixupResponse;
import com.platform.migrationservice.migration.model.MigrationItemStatus;
import com.platform.migrationservice.migration.model.MigrationStage;
import com.platform.migrationservice.migration.report.MigrationJobResponse;
import com.platform.migrationservice.migration.report.MigrationReportResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import com.platform.migrationservice.config.ApiFailures;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import static com.platform.migrationservice.config.JwtPrincipal.userId;

@Tag(name = "Migrations", description = """
        컨플루언스 설치형(Server/DC) 원본을 위키로 옮기는 이관 작업의 생성·실행·보고.
        연결 확인과 작업 목록은 전역 관리자만, 나머지는 대상 스페이스 ADMIN만 부를 수 있다.""")
@RestController
@RequestMapping("/api/migration")
@RequiredArgsConstructor
public class MigrationController {

    private final MigrationJobService migrations;

    /** 연결 확인 — 토큰은 요청 본문으로만 들어오고 응답에는 실리지 않는다. */
    @Operation(summary = "컨플루언스 설치형 원본에 연결되는지 확인한다 — 토큰은 응답에 실리지 않는다")
    @PostMapping("/confluence-dc/probe")
    public ConfluenceDcProbeResponse probeConfluenceDc(@AuthenticationPrincipal Jwt jwt,
                                                       @Valid @RequestBody ConfluenceDcProbeRequest req) {
        return migrations.probeConfluenceDc(userId(jwt), req);
    }

    /** 관리자용 잡 목록(최신순). */
    @Operation(summary = "이관 작업 목록을 최신순으로 조회한다")
    @GetMapping
    public List<MigrationJobSummary> list(@AuthenticationPrincipal Jwt jwt) {
        return migrations.list(userId(jwt));
    }

    @Operation(summary = "이관 작업을 만든다")
    // 대상 스페이스는 본문의 targetSpaceId가 가리키고 그 실재는 위키에 묻는다 — 경로 변수가
    // 없어 공통 규칙이 404를 붙이지 못한다.
    @ApiFailures("404")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MigrationJobResponse create(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody MigrationJobCreateRequest req) {
        return migrations.create(userId(jwt), req);
    }

    @Operation(summary = "이관할 원본 문서를 대기열에 넣는다")
    @ApiFailures("409")
    @PostMapping("/{jobId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public MigrationItemResponse enqueue(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId,
                                         @Valid @RequestBody MigrationItemEnqueueRequest req) {
        return migrations.enqueue(userId(jwt), jobId, req);
    }

    /** 원본 트리를 훑어 대기열을 채운다. 다시 눌러도 새 항목만 늘어난다(멱등). */
    @Operation(summary = "원본 트리를 훑어 대기열을 채운다 — 다시 눌러도 새 항목만 늘어난다")
    // 본문이 없어도 400이 난다 — 원본 응답을 이해할 수 없거나 주소가 다른 곳으로 넘길 때다.
    @ApiFailures({"400", "409"})
    @PostMapping("/{jobId}/discover")
    public MigrationDiscoverResponse discover(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId) {
        return migrations.discover(userId(jwt), jobId, Instant.now());
    }

    @Operation(summary = "이관 작업을 시작한다")
    // 본문이 없어도 400이 난다 — 발견을 건너뛴 채 시작하면 "옮길 항목이 없습니다"로 막힌다.
    @ApiFailures({"400", "409"})
    @PostMapping("/{jobId}/start")
    public MigrationJobResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId) {
        return migrations.start(userId(jwt), jobId, Instant.now());
    }

    @Operation(summary = "진행 중인 이관 작업을 취소한다")
    @ApiFailures("409")
    @PostMapping("/{jobId}/cancel")
    public MigrationJobResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId) {
        return migrations.cancel(userId(jwt), jobId, Instant.now());
    }

    @Operation(summary = "이관 작업의 진행 상황을 조회한다")
    @GetMapping("/{jobId}")
    public MigrationJobDetailResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId) {
        return migrations.detail(userId(jwt), jobId);
    }

    /** 실패 항목 표. status·stage는 선택 필터, page는 0부터. */
    @Operation(summary = "이관 항목을 상태·단계로 걸러 페이지 단위로 조회한다")
    @GetMapping("/{jobId}/items")
    public MigrationItemPageResponse items(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId,
                                           @Parameter(description = "항목 상태 필터. 비우면 전부")
                                           @RequestParam(required = false) MigrationItemStatus status,
                                           @Parameter(description = "이관 단계 필터. 비우면 전부")
                                           @RequestParam(required = false) MigrationStage stage,
                                           @Parameter(description = "0부터 세는 페이지 번호")
                                           @RequestParam(defaultValue = "0") int page) {
        return migrations.listItems(userId(jwt), jobId, status, stage, page);
    }

    /**
     * 잡이 끝난 뒤 도는 링크 정리만 다시 돌린다. 옮긴 문서를 다시 이관하지 않는다 —
     * 이미 정리된 문서에는 임시 링크가 없어 손대지 않으므로 다시 눌러도 안전하다.
     */
    @Operation(summary = "끝난 작업의 링크 정리를 다시 돌린다 — 다시 눌러도 안전하다")
    @ApiFailures("409")
    @PostMapping("/{jobId}/link-fixup")
    public MigrationLinkFixupResponse rerunLinkFixup(@AuthenticationPrincipal Jwt jwt,
                                                     @PathVariable long jobId) {
        return migrations.rerunLinkFixup(userId(jwt), jobId);
    }

    @Operation(summary = "이관 결과 보고서를 조회한다")
    @GetMapping("/{jobId}/report")
    public MigrationReportResponse report(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId) {
        return migrations.report(userId(jwt), jobId);
    }
}
