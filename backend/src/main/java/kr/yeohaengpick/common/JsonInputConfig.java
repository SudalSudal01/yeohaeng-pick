package kr.yeohaengpick.common;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 인원·금액·버전의 소수를 조용히 버리거나 null을 0/false로 바꾸지 않습니다.
 * HTTP 입력과 DB JSON 복원에 같은 규칙을 적용합니다. 선택 항목은 nullable DTO나 생성자의 기본값으로 명시합니다. */
@Configuration
public class JsonInputConfig {

  static void configure(JsonMapper.Builder builder) {
    builder.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    builder.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
  }

  static JsonMapper mapper() {
    var builder = JsonMapper.builder();
    configure(builder);
    return builder.build();
  }

  @Bean
  JsonMapperBuilderCustomizer integerInputs() {
    return JsonInputConfig::configure;
  }
}
