package kr.yeohaengpick.transport;

import java.time.*;
import java.util.*;
import kr.yeohaengpick.common.DomainException;
import org.springframework.stereotype.Component;

/**
 * 공급자 조회를 일정에 적용할 때 쓰는 짧은 수명의 서버 영수증입니다.
 * 브라우저가 보낸 분·좌표·요금을 다시 신뢰하지 않고, 조회한 회원과 여행 버전까지 묶어 검증합니다.
 * 지도 선과 검색 원문은 복제하지 않으며 만료/재시작 후에는 다시 조회해야 합니다.
 */
@Component
public class TransportQuotes {

  public record Quote(
    String trip,
    String member,
    int version,
    TransportService.Ref fromRef,
    TransportService.Ref toRef,
    TransportRoutes.Point from,
    TransportRoutes.Point to,
    String mode,
    String queriedAt,
    String source,
    List<TransportRoutes.Option> options,
    Instant expires
  ) {}

  private final Map<String, Quote> quotes = new LinkedHashMap<>();
  private final Clock clock;

  public TransportQuotes() {
    this(Clock.systemUTC());
  }

  TransportQuotes(Clock clock) {
    this.clock = clock;
  }

  public synchronized String issue(
    String trip,
    String member,
    int version,
    TransportService.Request request,
    TransportRoutes.Point from,
    TransportRoutes.Point to,
    String queriedAt,
    String source,
    List<TransportRoutes.Option> options
  ) {
    quotes
      .entrySet()
      .removeIf(e -> !e.getValue().expires().isAfter(Instant.now(clock)));
    if (quotes.size() >= 300) quotes.remove(quotes.keySet().iterator().next());
    String token = UUID.randomUUID().toString();
    var compact = options
      .stream()
      .map(o ->
        new TransportRoutes.Option(
          o.id(),
          o.mode(),
          o.distanceMeters(),
          o.minutes(),
          o.transfers(),
          o.fare(),
          o.toll(),
          o.walkingMinutes(),
          List.of(),
          List.of()
        )
      )
      .toList();
    quotes.put(
      token,
      new Quote(
        trip,
        member,
        version,
        request.from(),
        request.to(),
        from,
        to,
        request.mode(),
        queriedAt,
        source,
        compact,
        Instant.now(clock).plusSeconds(900)
      )
    );
    return token;
  }

  public synchronized Quote get(String token, String trip, String member) {
    var q = quotes.get(token);
    if (
      q == null ||
      !q.trip().equals(trip) ||
      !q.member().equals(member) ||
      !q.expires().isAfter(Instant.now(clock))
    ) throw new DomainException(
      409,
      "경로 조회가 만료됐거나 이 계정의 조회가 아니에요. 경로를 다시 조회해 주세요."
    );
    return q;
  }
}
