package kr.yeohaengpick.common;

import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** JSON 변환 책임을 한 곳에 둡니다. 취향 목록·변경 스냅샷만 JSON으로 보관하고 핵심 관계는 SQL로 관리합니다. */
@Component
public class JsonCodec {

  private final ObjectMapper mapper = new ObjectMapper();

  public String write(Object value) {
    return mapper.writeValueAsString(value);
  }

  public <T> T read(String value, Class<T> type) {
    return mapper.readValue(value, type);
  }

  @SuppressWarnings("unchecked")
  public List<String> strings(String value) {
    return read(value, List.class);
  }

  public ObjectMapper mapper() {
    return mapper;
  }
}
