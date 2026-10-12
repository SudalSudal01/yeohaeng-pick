package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import kr.yeohaengpick.trip.LodgingStay;
import org.junit.jupiter.api.Test;

/** 입력한 날짜/시각의 경계를 검사하고 모르는 시각을 임의 호텔 정책으로 만들지 않는지 확인합니다. */
class LodgingStayTest {

  @Test
  void exactCheckInAndCheckOutAreAllowedButOutsideIsBlocked() {
    var c = new LodgingStay.Conditions(
      LocalDate.parse("2026-11-01"),
      LocalDate.parse("2026-11-02"),
      2,
      1,
      180000,
      "15:00",
      "11:00"
    );
    assertThat(
      LodgingStay.conflicts(
        c,
        "호텔",
        LocalDateTime.parse("2026-11-01T15:00"),
        LocalDateTime.parse("2026-11-02T11:00")
      )
    ).isEmpty();
    assertThat(
      LodgingStay.conflicts(
        c,
        "호텔",
        LocalDateTime.parse("2026-11-01T14:59"),
        LocalDateTime.parse("2026-11-02T11:01")
      )
    ).hasSize(2);
  }

  @Test
  void unknownTimesOnlyConstrainDatesAndUnknownConditionsRemainCompatible() {
    var c = new LodgingStay.Conditions(
      LocalDate.parse("2026-11-01"),
      LocalDate.parse("2026-11-02"),
      2,
      1,
      null,
      null,
      null
    );
    assertThat(
      LodgingStay.conflicts(
        c,
        "호텔",
        LocalDateTime.parse("2026-11-01T01:00"),
        LocalDateTime.parse("2026-11-02T23:59")
      )
    ).isEmpty();
    assertThat(
      LodgingStay.conflicts(
        c,
        "호텔",
        null,
        LocalDateTime.parse("2026-11-03T00:00")
      )
    ).hasSize(1);
    assertThat(
      LodgingStay.conflicts(
        new LodgingStay.Conditions(null, null, null, null, null, null, null),
        "호텔",
        null,
        LocalDateTime.parse("2026-11-03T00:00")
      )
    ).isEmpty();
  }
}
