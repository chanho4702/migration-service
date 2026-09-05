package com.platform.migrationservice.migration.confluence.dc;

import java.util.List;

/** 페이지네이션 한 묶음. hasMore는 받은 수가 limit에 닿았는지로만 판단한다. */
public record ConfluenceContentPage(List<ConfluenceContentSummary> results, boolean hasMore) {
}
