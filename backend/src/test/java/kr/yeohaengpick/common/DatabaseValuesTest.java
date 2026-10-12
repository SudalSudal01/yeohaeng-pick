package kr.yeohaengpick.common;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import javax.sql.rowset.serial.SerialClob;
import org.junit.jupiter.api.Test;

/** DB 교체 때 권한/고정 상태, 빈 금액과 날짜/긴 본문의 의미가 달라지지 않아야 합니다. */
class DatabaseValuesTest {

  @Test
  void normalizesOracleFlagsAndNumbersWithoutConvertingUnknownNumbersToFlags() {
    assertThat(MyBatisConfig.value("fixed", BigDecimal.ONE)).isEqualTo(true);
    assertThat(MyBatisConfig.value("verified", BigDecimal.ZERO)).isEqualTo(
      false
    );
    assertThat(
      MyBatisConfig.value("guestCount", new BigDecimal("2"))
    ).isEqualTo(2);
    assertThat(MyBatisConfig.value("visitCost", null)).isNull();
    assertThat(MyBatisConfig.value("visitCost", BigDecimal.ZERO)).isEqualTo(0);
    assertThat(MyBatisConfig.value("latitude", 37.123456789d)).isEqualTo(
      37.123456789d
    );
  }

  @Test
  void preservesDateAndTimestampAndEmptyTextContracts() throws Exception {
    var time = Timestamp.valueOf("2026-10-12 00:00:00");
    assertThat(MyBatisConfig.value("startDate", time)).isEqualTo(
      LocalDate.of(2026, 10, 12)
    );
    assertThat(MyBatisConfig.value("createdAt", time)).isSameAs(time);
    assertThat(MyBatisConfig.value("visitNote", null)).isEqualTo("");
    assertThat(MyBatisConfig.value("deletedAt", null)).isNull();
    String text = "한글과 이모지 🧭\n".repeat(3000);
    assertThat(
      MyBatisConfig.value("body", new SerialClob(text.toCharArray()))
    ).isEqualTo(text);
  }
}
