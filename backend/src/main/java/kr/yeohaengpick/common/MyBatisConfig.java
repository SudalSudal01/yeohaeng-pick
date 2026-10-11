package kr.yeohaengpick.common;

import java.util.Map;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.wrapper.*;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.*;

/** MyBatis의 Map 결과에도 snake_case → camelCase를 적용해 Java/TypeScript API 계약을 맞춥니다. */
@Configuration
public class MyBatisConfig {

  @Bean
  ConfigurationCustomizer mapKeys() {
    return c ->
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
                for (char ch : name.toCharArray()) {
                  if (ch == '_') upper = true;
                  else {
                    key.append(upper ? Character.toUpperCase(ch) : ch);
                    upper = false;
                  }
                }
                return key.toString();
              }
            };
          }
        }
      );
  }
}
