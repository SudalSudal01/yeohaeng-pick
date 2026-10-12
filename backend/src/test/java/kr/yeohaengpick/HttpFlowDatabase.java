package kr.yeohaengpick;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 같은 HTTP 시나리오를 H2와 실제 Oracle QA 스키마에서 실행합니다. 실사용 계정은 거절합니다. */
abstract class HttpFlowDatabase {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    String url = System.getenv("TEST_ORACLE_URL");
    if (url == null || url.isBlank()) return;
    String user = System.getenv("TEST_ORACLE_USERNAME");
    String password = System.getenv("TEST_ORACLE_PASSWORD");
    if (
      !url.matches(
        "jdbc:oracle:thin:@//(localhost|127\\.0\\.0\\.1):1521/[a-zA-Z0-9._-]+"
      ) ||
      !"YEOHAENG_PICK_QA".equals(user) ||
      password == null ||
      password.isBlank()
    ) {
      throw new IllegalStateException(
        "Oracle HTTP 테스트는 로컬 YEOHAENG_PICK_QA 전용 계정에서만 허용합니다."
      );
    }
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.username", () -> user);
    registry.add("spring.datasource.password", () -> password);
    registry.add(
      "spring.datasource.driver-class-name",
      () -> "oracle.jdbc.OracleDriver"
    );
    registry.add(
      "spring.datasource.hikari.data-source-properties.oracle.jdbc.mapDateToTimestamp",
      () -> "false"
    );
    registry.add(
      "spring.datasource.hikari.data-source-properties.oracle.jdbc.J2EE13Compliant",
      () -> "true"
    );
    registry.add("spring.flyway.locations", () -> "classpath:db/oracle");
  }
}
