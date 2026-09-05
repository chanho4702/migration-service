package com.platform.migrationservice.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 오퍼레이션이 낼 수 있지만 {@link OpenApiConfig}의 공통 규칙으로는 알아낼 수 없는 실패 코드.
 *
 * {@code @ApiResponse}를 쓰지 않는 이유가 이 애너테이션의 존재 이유다. 핸들러에
 * {@code @ApiResponse}를 하나라도 달면 springdoc이 반환 타입에서 200을 자동 생성하지 않아,
 * 4xx만 선언하는 순간 성공 응답이 조용히 사라진다(wiki-backend가 회귀로 막아 둔 함정).
 * 여기서는 코드만 선언하고 응답 본문은 커스터마이저가 공통 오류 스키마로 채운다.
 *
 * 규칙으로 잡히는 것(요청 본문이 있으면 400, 경로 변수가 있으면 404, 전부에 401·403·503)은
 * 여기 적지 않는다 — 두 곳에 같은 사실이 있으면 갈라진다.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ApiFailures {

    /** HTTP 상태 코드 문자열. 예: {@code {"404", "409"}} */
    String[] value();
}
