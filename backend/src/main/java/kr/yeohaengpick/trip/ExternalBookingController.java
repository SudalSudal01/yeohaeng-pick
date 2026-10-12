package kr.yeohaengpick.trip;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.*;
import kr.yeohaengpick.auth.AuthService;
import kr.yeohaengpick.common.DomainException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 외부 예매 결과의 직접 입력 API. 제휴 확인 상태는 클라이언트가 임의로 만들 수 없습니다. */
@RestController
@RequestMapping("/api/trips/{trip}/external-bookings")
public class ExternalBookingController {

  private final TripService trips;
  private final TripMapper mapper;
  private final AuthService auth;

  public ExternalBookingController(TripService t, TripMapper m, AuthService a) {
    trips = t;
    mapper = m;
    auth = a;
  }

  public record Booking(
    String id,
    @NotBlank @Size(max = 100) String label,
    @NotNull @Pattern(regexp = "TRAIN|BUS|FLIGHT|CAR|OTHER") String category,
    @NotBlank @Size(max = 16) String departure,
    @Size(max = 16) String arrival,
    @Size(max = 100) String departurePlace,
    @Size(max = 100) String arrivalPlace,
    @Min(0) @Max(360) Integer beforeMinutes,
    @Min(0) @Max(360) Integer afterMinutes,
    Boolean scheduleLinked,
    @Min(0) @Max(100000000) Integer amount,
    @NotNull
    @Pattern(regexp = "NONE|NEEDS_CONFIRMATION|USER_CONFIRMED|FAILED|CANCELLED")
    String status,
    @NotNull @Size(max = 150) String reason,
    @Min(0) int version
  ) {}

  @PostMapping
  @Transactional
  Object save(
    @PathVariable String trip,
    @Valid @RequestBody Booking b,
    Authentication a
  ) {
    String member = auth.id(a);
    var t = trips.access(trip, member);
    LocalDateTime departure;
    LocalDateTime arrival = null;
    try {
      departure = LocalDateTime.parse(b.departure);
      if (b.arrival != null && !b.arrival.isBlank()) arrival =
        LocalDateTime.parse(b.arrival);
    } catch (java.time.DateTimeException e) {
      throw DomainException.bad("출발·도착 일시를 확인해 주세요.");
    }
    var start = java.time.LocalDate.parse(t.get("startDate").toString());
    var end = java.time.LocalDate.parse(t.get("endDate").toString());
    if (
      departure.toLocalDate().isBefore(start) ||
      departure.toLocalDate().isAfter(end)
    ) throw DomainException.bad("출발 일시는 여행 기간 안에서 선택해 주세요.");
    // 저장한 일시는 한국 현지 시각입니다. 자정을 넘는 이동은 날짜까지 입력해야 합니다.
    if (
      arrival != null &&
      (!arrival.isAfter(departure) || arrival.toLocalDate().isAfter(end))
    ) throw DomainException.bad(
      "도착은 출발 이후이며 여행 기간 안이어야 해요. 자정을 넘으면 도착 날짜도 바꿔 주세요."
    );
    if (
      Boolean.TRUE.equals(b.scheduleLinked) &&
      b.status.equals("USER_CONFIRMED") &&
      arrival == null
    ) throw DomainException.bad(
      "일정에 연결할 예약 완료 교통편은 도착 일시도 입력해 주세요."
    );
    String id = b.id == null ? UUID.randomUUID().toString() : b.id;
    if (
      b.id != null &&
      mapper
        .externalBookings(trip)
        .stream()
        .noneMatch(x -> x.get("id").equals(id))
    ) throw new DomainException(404, "예약 기록을 찾을 수 없어요.");
    if (mapper.bump(trip, b.version) != 1) throw new DomainException(
      409,
      "다른 변경이 먼저 저장됐어요. 최신 여행을 확인해 주세요."
    );
    if (b.id == null) mapper.addExternalBooking(id, trip, b, member);
    else mapper.updateExternalBooking(id, trip, b, member);
    return trips.detail(trip, member);
  }
}
