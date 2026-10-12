package kr.yeohaengpick.transport;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import kr.yeohaengpick.common.DomainException;
import org.junit.jupiter.api.Test;

/** 실제 대기 없이 만료 경계와 회원·여행 범위 및 메모리 보관 상한을 확인합니다. */
class TransportQuotesTest {

  static class TestClock extends Clock {

    Instant now = Instant.parse("2026-10-12T01:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  String issue(TransportQuotes quotes) {
    return quotes.issue(
      "trip",
      "member",
      3,
      new TransportService.Request(
        new TransportService.Ref("ITEM", "a"),
        new TransportService.Ref("ITEM", "b"),
        "WALK"
      ),
      new TransportRoutes.Point("a", "a", 37.5, 127),
      new TransportRoutes.Point("b", "b", 37.6, 127),
      "2026-10-12T01:00:00Z",
      "KAKAO_MAP",
      List.of()
    );
  }

  @Test
  void receiptIsBoundToMemberTripAndFifteenMinuteExpiry() {
    var clock = new TestClock();
    var quotes = new TransportQuotes(clock);
    String token = issue(quotes);
    assertThatThrownBy(() -> quotes.get(token, "other", "member")).isInstanceOf(
      DomainException.class
    );
    assertThatThrownBy(() -> quotes.get(token, "trip", "other")).isInstanceOf(
      DomainException.class
    );
    clock.now = clock.now.plusSeconds(899);
    assertThat(quotes.get(token, "trip", "member").version()).isEqualTo(3);
    clock.now = clock.now.plusSeconds(1);
    assertThatThrownBy(() -> quotes.get(token, "trip", "member")).isInstanceOf(
      DomainException.class
    );
  }

  @Test
  void boundedMemoryDropsOldestReceipt() {
    var quotes = new TransportQuotes(new TestClock());
    String first = issue(quotes);
    String last = "";
    for (int i = 0; i < 300; i++) last = issue(quotes);
    assertThatThrownBy(() -> quotes.get(first, "trip", "member")).isInstanceOf(
      DomainException.class
    );
    assertThat(quotes.get(last, "trip", "member").version()).isEqualTo(3);
  }
}
