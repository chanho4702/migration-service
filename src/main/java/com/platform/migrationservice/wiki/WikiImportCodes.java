package com.platform.migrationservice.wiki;

/** 위키 import API 호출이 실패했을 때의 단계 코드. 손실 보고서의 집계 키가 된다. */
public final class WikiImportCodes {

    /**
     * 위키에 닿지 못했다(연결 실패·타임아웃·5xx·429). 재시도 대상이다 — 여기서 "못 옮겼다"로
     * 삼키면 문서가 빠진 잡이 성공으로 끝난다.
     */
    public static final String UNAVAILABLE = "WIKI_IMPORT_UNAVAILABLE";

    /**
     * 위키가 요청을 거부했다(4xx). 같은 본문을 다시 보내도 같은 답이 오므로 재시도하지 않는다 —
     * 내부 토큰 불일치(401/403)와 계약 위반(400)이 여기로 온다.
     */
    public static final String REJECTED = "WIKI_IMPORT_REJECTED";

    /** 대상 문서·스페이스가 위키에 없다. 사람이 지웠거나 잡 설정이 틀렸다. */
    public static final String NOT_FOUND = "WIKI_IMPORT_NOT_FOUND";

    private WikiImportCodes() {
    }
}
