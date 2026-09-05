package com.platform.migrationservice.migration.confluence.dc;

import java.util.List;

/**
 * 발견 단계가 보는 페이지 한 건. ancestors는 루트에서 부모 순서(DC가 그 순서로 준다)라
 * 크기가 곧 트리 깊이이고, 마지막 항목이 부모다.
 */
public record ConfluenceContentSummary(String id, String title, int version, List<String> ancestors) {

    public int depth() {
        return ancestors.size();
    }

    public String parentId() {
        return ancestors.isEmpty() ? null : ancestors.get(ancestors.size() - 1);
    }
}
