package com.platform.migrationservice.migration.ir;

/**
 * 마크다운으로 쓸 때만 필요한 원본 쪽 맥락.
 *
 * IR 자체에는 "이 문서가 어느 스페이스에서 왔는가"가 source.instanceId까지만 있고, 페이지 링크가
 * 같은 스페이스인지(=`[[제목]]`으로 열리는지) 판정하려면 원본 스페이스 키가 필요하다.
 * baseUrl은 다른 스페이스로 가는 링크를 원본 URL로 남길 때만 쓴다.
 */
public record DocumentIrMarkdownContext(String sourceSpaceKey, String sourceBaseUrl) {

    public static DocumentIrMarkdownContext none() {
        return new DocumentIrMarkdownContext(null, null);
    }
}
