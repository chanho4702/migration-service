package com.platform.migrationservice.migration;

import com.platform.migrationservice.migration.repository.MigrationIssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앞 단계가 남긴 손실 기록을 지운다.
 *
 * MEDIA_COPY가 첨부를 옮기고 나면 IR을 다시 만드는데(자산이 해결됐으므로), 그때 NORMALIZE가 남긴
 * "이미지를 못 옮겼다" 기록은 **틀린 말이 된다**. 두 번째 정규화 결과가 정본이므로 첫 패스의 것을
 * 지우고 새로 남긴다 — 안 지우면 보고서가 옮겨진 파일을 손실로 세고, 관리자는 성공한 이관을
 * 실패로 읽는다.
 *
 * handler가 트랜잭션 밖에서 부르므로 스스로 트랜잭션을 연다.
 */
@Component
@RequiredArgsConstructor
public class MigrationIssueReset {

    /** 정규화기가 내는 코드는 전부 이 접두사를 쓴다. */
    public static final String NORMALIZATION_CODE_PREFIX = "CONFLUENCE_";

    private final MigrationIssueRepository issues;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int clearNormalizationIssues(long itemId) {
        return issues.deleteByItemIdAndCodeStartingWith(itemId, NORMALIZATION_CODE_PREFIX);
    }
}
