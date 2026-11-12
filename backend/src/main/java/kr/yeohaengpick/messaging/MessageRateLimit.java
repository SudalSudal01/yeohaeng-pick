package kr.yeohaengpick.messaging;

import java.util.concurrent.ConcurrentHashMap;
import kr.yeohaengpick.common.DomainException;
import org.springframework.stereotype.Component;

/**
 * 로그인 회원별 검색·요청·전송 예산입니다. 검색은 짧은 입력으로 전체 회원을 반복 열거하지 못하게 제한합니다.
 * 단일 JVM의 분당 제한이며 다중 서버를 배포할 때는 같은 키를 공유 저장소에서 검사해야 합니다.
 * 만료 항목을 지우고 저장 크기를 제한해 키가 계속 쌓이는 문제도 방지합니다.
 */
@Component
public class MessageRateLimit {

  private record Window(long start, int count) {}

  private final ConcurrentHashMap<String, Window> windows =
    new ConcurrentHashMap<>();

  public void check(String member, String action, int limit) {
    long now = System.currentTimeMillis();
    windows
      .entrySet()
      .removeIf(entry -> now - entry.getValue().start() >= 60000);
    String key = member + ":" + action;
    if (windows.size() >= 10000 && !windows.containsKey(key)) throw limited();
    Window next = windows.compute(key, (unused, old) ->
      old == null || now - old.start() >= 60000
        ? new Window(now, 1)
        : new Window(old.start(), old.count() + 1)
    );
    if (next.count() > limit) throw limited();
  }

  private DomainException limited() {
    return new DomainException(
      429,
      "요청이 많아요. 1분 후 다시 시도해 주세요."
    );
  }
}
