package kr.yeohaengpick.common;

import java.math.BigDecimal;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.*;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.reflection.wrapper.*;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.*;

/** MyBatis의 Map 결과에도 snake_case → camelCase를 적용해 Java/TypeScript API 계약을 맞춥니다. */
@Configuration
public class MyBatisConfig {

  /** 19c에는 SQL BOOLEAN이 없으므로 해당 매퍼의 명시적인 NUMBER(1) SQL을 선택합니다. */
  @Bean
  DatabaseIdProvider databaseIdProvider() {
    var provider = new VendorDatabaseIdProvider();
    var names = new Properties();
    names.setProperty("Oracle", "oracle");
    provider.setProperties(names);
    return provider;
  }

  private static final Set<String> FLAGS = Set.of(
    "verified",
    "hidden",
    "indoor",
    "fixed",
    "done",
    "scheduleLinked"
  );
  private static final Set<String> DATES = Set.of(
    "startDate",
    "endDate",
    "recruitmentDate",
    "checkInDate",
    "checkOutDate"
  );
  // Oracle VARCHAR2의 빈 문자열은 NULL입니다. 원래 빈 값을 허용하던 필드에 한해서만
  // API의 "" 계약으로 복원합니다. 날짜/금액/삭제 시각의 의미 있는 NULL은 그대로 둡니다.
  private static final Set<String> EMPTY_TEXT = Set.of(
    "lodging",
    "placeUrl",
    "bookingReason",
    "departurePlace",
    "arrivalPlace",
    "visitNote",
    "reason",
    "resolution",
    "region",
    "body"
  );

  static Object value(String key, Object value) {
    if (value == null) return EMPTY_TEXT.contains(key) ? "" : null;
    // Oracle DATE는 JDBC 메타데이터에서 TIMESTAMP로 보일 수도 있습니다. 달력 날짜 열만
    // LocalDate로 정규화하고, 생성/수정/만료 시각에는 이 변환을 적용하지 않습니다.
    if (
      DATES.contains(key) && value instanceof java.sql.Timestamp date
    ) return date.toLocalDateTime().toLocalDate();
    if (
      DATES.contains(key) && value instanceof java.sql.Date date
    ) return date.toLocalDate();
    if (FLAGS.contains(key) && value instanceof Number number) return (
      number.intValue() != 0
    );
    if (
      value instanceof BigDecimal number && number.scale() <= 0
    ) return number.intValueExact();
    if (value instanceof Clob text) {
      try {
        return text.getSubString(1, Math.toIntExact(text.length()));
      } catch (SQLException e) {
        throw new IllegalStateException("DB의 긴 본문을 읽지 못했습니다.", e);
      }
    }
    return value;
  }

  @Bean
  ConfigurationCustomizer mapKeys() {
    return c -> {
      // 긴 본문/JSON의 JDBC CLOB 바인딩만 전용 처리합니다. 일반 검색어·이메일·ID는 VARCHAR 그대로입니다.
      c.getTypeHandlerRegistry().register(
        String.class,
        org.apache.ibatis.type.JdbcType.CLOB,
        new LongTextTypeHandler()
      );
      // NULL 열도 전달받아 Oracle의 빈 문자열만 선별 복원합니다.
      c.setCallSettersOnNulls(true);
      c.setObjectWrapperFactory(
        new ObjectWrapperFactory() {
          public boolean hasWrapperFor(Object o) {
            return o instanceof Map;
          }

          @SuppressWarnings("unchecked")
          public ObjectWrapper getWrapperFor(MetaObject m, Object o) {
            return new MapWrapper(m, (Map<String, Object>) o) {
              @Override
              public String findProperty(String name, boolean camel) {
                StringBuilder key = new StringBuilder();
                boolean upper = false;
                // Oracle은 따옴표 없는 열 이름을 대문자로 반환합니다.
                for (char ch : name.toLowerCase(Locale.ROOT).toCharArray()) {
                  if (ch == '_') upper = true;
                  else {
                    key.append(upper ? Character.toUpperCase(ch) : ch);
                    upper = false;
                  }
                }
                return key.toString();
              }

              @Override
              public void set(PropertyTokenizer property, Object object) {
                super.set(property, value(property.getName(), object));
              }
            };
          }
        }
      );
    };
  }
}
