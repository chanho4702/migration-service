# Third-Party Notices — migration-service

이 문서는 `migration-service`의 **실행 이미지(bootJar)에 포함되는 서드파티 라이브러리**(`runtimeClasspath`)와 POM에 선언된 라이선스 목록이다.
각 라이브러리의 저작권 고지·NOTICE·라이선스 전문은 해당 jar의 `META-INF/`와 아래 프로젝트 URL에 있다.

- 생성: `infra-settings/scripts/notices/New-BackendNotices.ps1` (기준 build.gradle sha256 6591c908b44d, 서브프로젝트: (단일))
- 라이브러리 수: 182
- 컨테이너 베이스 이미지(eclipse-temurin, GPL-2.0 with Classpath Exception)와 플랫폼이 함께 띄우는 이미지 목록은 `infra-settings/THIRD-PARTY-NOTICES.md`.
- 상표: Jira, Confluence, Atlassian은 Atlassian Pty Ltd의 상표이며 본 제품은 Atlassian과 무관합니다.

## 검토 필요 (카피레프트·소스공개·비자유·미확인 라이선스)

해당 없음.

## 라이선스별 요약

| 라이선스 | 라이브러리 수 |
|---|---|
| Apache-2.0 | 148 |
| BSD (조항 수 수동 확인) | 2 |
| BSD-2-Clause | 1 |
| BSD-3-Clause | 3 |
| BSD-3-Clause (수동 판정: https://www.antlr3.org/license.html) | 1 |
| EDL-1.0 (BSD-3-Clause) | 7 |
| EPL-2.0 | 1 |
| EPL-2.0 OR EDL-1.0 (BSD-3-Clause) | 1 |
| EPL-2.0 OR GPL-2.0-with-classpath-exception | 3 |
| EPL-2.0 OR LGPL | 2 |
| GPL-2.0-with-classpath-exception | 1 |
| Indiana University Extreme! Lab Software License | 1 |
| MIT | 5 |
| MIT (플랫폼 자체 모듈) | 2 |
| MIT (Bouncy Castle) | 1 |
| Public Domain / CC0 | 2 |
| Public Domain / CC0 OR BSD-2-Clause | 1 |

## Apache-2.0

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| com.fasterxml:classmate | 1.7.3 | https://github.com/FasterXML/java-classmate |
| com.fasterxml.jackson:jackson-bom | 2.21.2 | https://github.com/FasterXML/jackson-bom |
| com.fasterxml.jackson.core:jackson-annotations | 2.21 | https://github.com/FasterXML/jackson |
| com.fasterxml.jackson.core:jackson-core | 2.21.2 | https://github.com/FasterXML/jackson-core |
| com.fasterxml.jackson.core:jackson-databind | 2.21.2 | https://github.com/FasterXML/jackson |
| com.fasterxml.jackson.dataformat:jackson-dataformat-yaml | 2.21.2 | https://github.com/FasterXML/jackson-dataformats-text |
| com.fasterxml.jackson.datatype:jackson-datatype-jdk8 | 2.21.2 | https://github.com/FasterXML/jackson-modules-java8 |
| com.fasterxml.jackson.datatype:jackson-datatype-jsr310 | 2.21.2 | https://github.com/FasterXML/jackson-modules-java8 |
| com.fasterxml.jackson.module:jackson-module-parameter-names | 2.21.2 | https://github.com/FasterXML/jackson-modules-java8 |
| com.github.ben-manes.caffeine:caffeine | 3.2.3 | https://github.com/ben-manes/caffeine |
| com.google.android:annotations | 4.1.1.4 | http://source.android.com/ |
| com.google.api.grpc:proto-google-common-protos | 2.48.0 | https://github.com/googleapis/sdk-platform-java |
| com.google.code.findbugs:jsr305 | 3.0.2 | http://findbugs.sourceforge.net/ |
| com.google.code.gson:gson | 2.13.2 |  |
| com.google.errorprone:error_prone_annotations | 2.43.0 |  |
| com.google.guava:failureaccess | 1.0.2 | https://github.com/google/guava |
| com.google.guava:guava | 33.3.1-android | https://github.com/google/guava |
| com.google.guava:listenablefuture | 9999.0-empty-to-avoid-conflict-with-guava | https://github.com/google/guava |
| com.google.j2objc:j2objc-annotations | 3.0.0 | https://github.com/google/j2objc/ |
| com.netflix.eureka:eureka-client | 2.0.6 | https://github.com/Netflix/eureka |
| com.netflix.netflix-commons:netflix-eventbus | 0.3.0 | https://github.com/Netflix/netflix-commons |
| com.netflix.netflix-commons:netflix-infix | 0.3.0 | https://github.com/Netflix/netflix-commons |
| com.netflix.servo:servo-core | 0.5.3 | https://github.com/Netflix/OSS-netflix-servo-release |
| com.netflix.spectator:spectator-api | 1.7.3 | https://github.com/Netflix/spectator |
| com.nimbusds:nimbus-jose-jwt | 10.4 | https://bitbucket.org/connect2id/nimbus-jose-jwt |
| com.stoyanr:evictor | 1.0.0 | https://github.com/stoyanr/Evictor |
| com.zaxxer:HikariCP | 7.0.2 | https://github.com/brettwooldridge/HikariCP |
| commons-codec:commons-codec | 1.19.0 | https://commons.apache.org/proper/commons-codec/ |
| commons-jxpath:commons-jxpath | 1.3 | http://commons.apache.org/jxpath/ |
| commons-logging:commons-logging | 1.3.6 | https://commons.apache.org/proper/commons-logging/ |
| io.grpc:grpc-api | 1.69.0 | https://github.com/grpc/grpc-java |
| io.grpc:grpc-bom | 1.69.0 | https://github.com/grpc/grpc-java |
| io.grpc:grpc-context | 1.69.0 | https://github.com/grpc/grpc-java |
| io.grpc:grpc-core | 1.69.0 | https://github.com/grpc/grpc-java |
| io.grpc:grpc-netty-shaded | 1.69.0 | https://github.com/grpc/grpc-java |
| io.grpc:grpc-protobuf | 1.69.0 | https://github.com/grpc/grpc-java |
| io.grpc:grpc-protobuf-lite | 1.69.0 | https://github.com/grpc/grpc-java |
| io.grpc:grpc-stub | 1.69.0 | https://github.com/grpc/grpc-java |
| io.grpc:grpc-util | 1.69.0 | https://github.com/grpc/grpc-java |
| io.micrometer:micrometer-commons | 1.16.5 | https://github.com/micrometer-metrics/micrometer |
| io.micrometer:micrometer-core | 1.16.5 | https://github.com/micrometer-metrics/micrometer |
| io.micrometer:micrometer-jakarta9 | 1.16.5 | https://github.com/micrometer-metrics/micrometer |
| io.micrometer:micrometer-observation | 1.16.5 | https://github.com/micrometer-metrics/micrometer |
| io.perfmark:perfmark-api | 0.27.0 | https://github.com/perfmark/perfmark |
| io.projectreactor:reactor-core | 3.8.5 | https://github.com/reactor/reactor-core |
| io.projectreactor.addons:reactor-extra | 3.6.0 | https://github.com/reactor/reactor-addons |
| io.swagger.core.v3:swagger-annotations-jakarta | 2.2.47 | https://github.com/swagger-api/swagger-core |
| io.swagger.core.v3:swagger-core-jakarta | 2.2.47 | https://github.com/swagger-api/swagger-core |
| io.swagger.core.v3:swagger-models-jakarta | 2.2.47 | https://github.com/swagger-api/swagger-core |
| jakarta.inject:jakarta.inject-api | 2.0.1 | https://github.com/eclipse-ee4j/injection-api |
| jakarta.validation:jakarta.validation-api | 3.1.1 | https://beanvalidation.org |
| joda-time:joda-time | 2.3 | http://www.joda.org/joda-time/ |
| net.bytebuddy:byte-buddy | 1.17.8 | https://bytebuddy.net |
| org.apache.commons:commons-lang3 | 3.19.0 | https://commons.apache.org/proper/commons-lang/ |
| org.apache.commons:commons-math | 2.2 | http://commons.apache.org/math/ |
| org.apache.httpcomponents:httpclient | 4.5.3 | http://hc.apache.org/httpcomponents-client |
| org.apache.httpcomponents:httpcore | 4.4.16 | http://hc.apache.org/httpcomponents-core-ga |
| org.apache.httpcomponents.client5:httpclient5 | 5.5.2 | https://hc.apache.org/httpcomponents-client-5.5.x/${project.version}/ |
| org.apache.httpcomponents.core5:httpcore5 | 5.3.6 | https://hc.apache.org/httpcomponents-core-5.3.x/${project.version}/ |
| org.apache.httpcomponents.core5:httpcore5-h2 | 5.3.6 | https://hc.apache.org/httpcomponents-core-5.3.x/${project.version}/ |
| org.apache.logging.log4j:log4j-api | 2.25.4 | https://logging.apache.org/log4j/2.x/ |
| org.apache.logging.log4j:log4j-to-slf4j | 2.25.4 | https://logging.apache.org/log4j/2.x/ |
| org.apache.tomcat.embed:tomcat-embed-core | 11.0.21 | https://tomcat.apache.org/ |
| org.apache.tomcat.embed:tomcat-embed-el | 11.0.21 | https://tomcat.apache.org/ |
| org.apache.tomcat.embed:tomcat-embed-websocket | 11.0.21 | https://tomcat.apache.org/ |
| org.codehaus.jettison:jettison | 1.5.4 | https://github.com/jettison-json/jettison |
| org.flywaydb:flyway-core | 11.14.1 | https://flywaydb.org |
| org.flywaydb:flyway-database-postgresql | 11.14.1 | https://flywaydb.org |
| org.hibernate.models:hibernate-models | 1.0.1 | https://github.com/hibernate/hibernate-models |
| org.hibernate.orm:hibernate-core | 7.2.12.Final | https://hibernate.org/orm |
| org.hibernate.validator:hibernate-validator | 9.0.1.Final | https://hibernate.org/validator |
| org.jboss.logging:jboss-logging | 3.6.3.Final | https://www.jboss.org |
| org.jspecify:jspecify | 1.0.0 | http://jspecify.org/ |
| org.springdoc:springdoc-openapi-starter-common | 3.0.3 | https://springdoc.org/ |
| org.springdoc:springdoc-openapi-starter-webmvc-api | 3.0.3 | https://springdoc.org/ |
| org.springframework:spring-aop | 7.0.7 | https://github.com/spring-projects/spring-framework |
| org.springframework:spring-aspects | 7.0.7 | https://github.com/spring-projects/spring-framework |
| org.springframework:spring-beans | 7.0.7 | https://github.com/spring-projects/spring-framework |
| org.springframework:spring-context | 7.0.7 | https://github.com/spring-projects/spring-framework |
| org.springframework:spring-context-support | 7.0.7 | https://github.com/spring-projects/spring-framework |
| org.springframework:spring-core | 7.0.7 | https://github.com/spring-projects/spring-framework |
| org.springframework:spring-expression | 7.0.7 | https://github.com/spring-projects/spring-framework |
| org.springframework:spring-jdbc | 7.0.7 | https://github.com/spring-projects/spring-framework |
| org.springframework:spring-orm | 7.0.7 | https://github.com/spring-projects/spring-framework |
| org.springframework:spring-tx | 7.0.7 | https://github.com/spring-projects/spring-framework |
| org.springframework:spring-web | 7.0.7 | https://github.com/spring-projects/spring-framework |
| org.springframework:spring-webmvc | 7.0.7 | https://github.com/spring-projects/spring-framework |
| org.springframework.boot:spring-boot | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-actuator | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-actuator-autoconfigure | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-autoconfigure | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-cache | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-data-commons | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-data-jpa | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-flyway | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-health | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-hibernate | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-http-converter | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-jackson | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-jackson2 | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-jdbc | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-jpa | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-micrometer-metrics | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-micrometer-observation | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-persistence | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-security | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-security-oauth2-resource-server | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-servlet | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-sql | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-starter | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-starter-actuator | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-starter-cache | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-starter-data-jpa | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-starter-jackson | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-starter-jdbc | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-starter-logging | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-starter-micrometer-metrics | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-starter-oauth2-resource-server | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-starter-security | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-starter-tomcat | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-starter-tomcat-runtime | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-starter-validation | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-starter-web | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-tomcat | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-transaction | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-validation | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-web-server | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.boot:spring-boot-webmvc | 4.0.6 | https://spring.io/projects/spring-boot |
| org.springframework.cloud:spring-cloud-commons | 5.0.2 | https://projects.spring.io/spring-cloud/spring-cloud-commons/ |
| org.springframework.cloud:spring-cloud-context | 5.0.2 | https://projects.spring.io/spring-cloud/spring-cloud-context/ |
| org.springframework.cloud:spring-cloud-loadbalancer | 5.0.2 | https://projects.spring.io/spring-cloud/spring-cloud-loadbalancer/ |
| org.springframework.cloud:spring-cloud-netflix-eureka-client | 5.0.2 | https://github.com/spring-cloud/spring-cloud-netflix/spring-cloud-netflix-eureka-client |
| org.springframework.cloud:spring-cloud-starter | 5.0.2 | https://projects.spring.io/spring-cloud |
| org.springframework.cloud:spring-cloud-starter-loadbalancer | 5.0.2 | https://projects.spring.io/spring-cloud |
| org.springframework.cloud:spring-cloud-starter-netflix-eureka-client | 5.0.2 | https://projects.spring.io/spring-cloud |
| org.springframework.data:spring-data-commons | 4.0.5 | https://spring.io/projects/spring-data |
| org.springframework.data:spring-data-jpa | 4.0.5 | https://projects.spring.io/spring-data-jpa |
| org.springframework.security:spring-security-config | 7.0.5 | https://spring.io/projects/spring-security |
| org.springframework.security:spring-security-core | 7.0.5 | https://spring.io/projects/spring-security |
| org.springframework.security:spring-security-crypto | 7.0.5 | https://spring.io/projects/spring-security |
| org.springframework.security:spring-security-oauth2-core | 7.0.5 | https://spring.io/projects/spring-security |
| org.springframework.security:spring-security-oauth2-jose | 7.0.5 | https://spring.io/projects/spring-security |
| org.springframework.security:spring-security-oauth2-resource-server | 7.0.5 | https://spring.io/projects/spring-security |
| org.springframework.security:spring-security-web | 7.0.5 | https://spring.io/projects/spring-security |
| org.yaml:snakeyaml | 2.5 | https://bitbucket.org/snakeyaml/snakeyaml |
| tools.jackson:jackson-bom | 3.1.2 | https://github.com/FasterXML/jackson-bom |
| tools.jackson.core:jackson-core | 3.1.2 | https://github.com/FasterXML/jackson-core |
| tools.jackson.core:jackson-databind | 3.1.2 | https://github.com/FasterXML/jackson |

## BSD (조항 수 수동 확인)

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| antlr:antlr | 2.7.7 | http://www.antlr.org/ |
| org.antlr:stringtemplate | 3.2.1 | http://www.stringtemplate.org |

## BSD-2-Clause

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| org.postgresql:postgresql | 42.7.10 | https://jdbc.postgresql.org |

## BSD-3-Clause

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| com.google.protobuf:protobuf-java | 4.29.3 | https://developers.google.com/protocol-buffers/ |
| com.thoughtworks.xstream:xstream | 1.4.21 | http://x-stream.github.io |
| org.antlr:antlr4-runtime | 4.13.2 | https://www.antlr.org/ |

## BSD-3-Clause (수동 판정: https://www.antlr3.org/license.html)

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| org.antlr:antlr-runtime | 3.4 | http://www.antlr.org |

## EDL-1.0 (BSD-3-Clause)

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| com.sun.istack:istack-commons-runtime | 4.1.2 |  |
| jakarta.activation:jakarta.activation-api | 2.1.4 | https://github.com/jakartaee/jaf-api |
| jakarta.xml.bind:jakarta.xml.bind-api | 4.0.4 | https://github.com/jakartaee/jaxb-api |
| org.eclipse.angus:angus-activation | 2.0.3 | https://github.com/eclipse-ee4j/angus-activation |
| org.glassfish.jaxb:jaxb-core | 4.0.6 | https://eclipse-ee4j.github.io/jaxb-ri/ |
| org.glassfish.jaxb:jaxb-runtime | 4.0.6 | https://eclipse-ee4j.github.io/jaxb-ri/ |
| org.glassfish.jaxb:txw2 | 4.0.6 | https://eclipse-ee4j.github.io/jaxb-ri/ |

## EPL-2.0

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| org.aspectj:aspectjweaver | 1.9.25.1 | https://www.eclipse.org/aspectj/ |

## EPL-2.0 OR EDL-1.0 (BSD-3-Clause)

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| jakarta.persistence:jakarta.persistence-api | 3.2.0 | https://github.com/jakartaee/persistence |

## EPL-2.0 OR GPL-2.0-with-classpath-exception

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| jakarta.annotation:jakarta.annotation-api | 3.0.0 | https://projects.eclipse.org/projects/ee4j.ca |
| jakarta.transaction:jakarta.transaction-api | 2.0.1 | https://projects.eclipse.org/projects/ee4j.jta |
| jakarta.ws.rs:jakarta.ws.rs-api | 4.0.0 | https://github.com/jakartaee/rest |

## EPL-2.0 OR LGPL

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| ch.qos.logback:logback-classic | 1.5.32 | http://logback.qos.ch |
| ch.qos.logback:logback-core | 1.5.32 | http://logback.qos.ch |

## GPL-2.0-with-classpath-exception

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| javax.annotation:javax.annotation-api | 1.2 | http://jcp.org/en/jsr/detail?id=250 |

## Indiana University Extreme! Lab Software License

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| io.github.x-stream:mxparser | 1.2.2 | http://x-stream.github.io/mxparser |

## MIT

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| org.checkerframework:checker-qual | 3.52.0 | https://checkerframework.org/ |
| org.codehaus.mojo:animal-sniffer-annotations | 1.24 | https://www.mojohaus.org/animal-sniffer |
| org.reactivestreams:reactive-streams | 1.0.4 | http://www.reactive-streams.org/ |
| org.slf4j:jul-to-slf4j | 2.0.17 | http://www.slf4j.org |
| org.slf4j:slf4j-api | 2.0.17 | http://www.slf4j.org |

## MIT (플랫폼 자체 모듈)

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| com.platform:common-proto | 0.17.0 |  |
| com.platform:common-starter | 0.17.0 |  |

## MIT (Bouncy Castle)

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| org.bouncycastle:bcprov-jdk18on | 1.81.1 | https://www.bouncycastle.org/download/bouncy-castle-java/ |

## Public Domain / CC0

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| org.latencyutils:LatencyUtils | 2.0.3 | http://latencyutils.github.io/LatencyUtils/ |
| xmlpull:xmlpull | 1.1.3.1 | http://www.xmlpull.org |

## Public Domain / CC0 OR BSD-2-Clause

| 라이브러리 | 버전 | 프로젝트 URL |
|---|---|---|
| org.hdrhistogram:HdrHistogram | 2.2.2 | http://hdrhistogram.github.io/HdrHistogram/ |
