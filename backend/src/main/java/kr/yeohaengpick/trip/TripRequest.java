package kr.yeohaengpick.trip;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

/** 여행별 조건은 기본 취향과 독립적입니다. 최대 14일/5개 지역으로 연산량과 실수를 제한합니다. */
public record TripRequest(
  @NotBlank @Size(max = 100) String title,
  @NotEmpty @Size(max = 5) List<@NotBlank @Size(max = 30) String> regions,
  @NotNull LocalDate startDate,
  @NotNull LocalDate endDate,
  @NotBlank @Size(max = 150) String origin,
  @Min(1) @Max(20) int partySize,
  @Min(10000) @Max(100000000) int budget,
  @NotNull @Pattern(regexp = "TRANSIT|CAR|WALK") String transport,
  @NotNull @Size(max = 12) List<@Size(max = 30) String> preferences,
  @Size(max = 150) String lodging,
  @Size(max = 5) List<@NotBlank @Size(max = 100) String> lodgingIds,
  @Size(max = 5) List<@NotBlank @Size(max = 100) String> favoriteIds,
  @Size(max = 5) List<@NotBlank @Size(max = 100) String> favoriteLodgingIds
) {
  public TripRequest {
    lodgingIds = lodgingIds == null ? List.of() : List.copyOf(lodgingIds);
    favoriteIds = favoriteIds == null ? List.of() : List.copyOf(favoriteIds);
    favoriteLodgingIds =
      favoriteLodgingIds == null ? List.of() : List.copyOf(favoriteLodgingIds);
  }

  /** 기존 내부 호출과 가져오기 양식은 찜 선택 없이 같은 계약을 유지합니다. */
  public TripRequest(
    String title,
    List<String> regions,
    LocalDate startDate,
    LocalDate endDate,
    String origin,
    int partySize,
    int budget,
    String transport,
    List<String> preferences,
    String lodging,
    List<String> lodgingIds
  ) {
    this(
      title,
      regions,
      startDate,
      endDate,
      origin,
      partySize,
      budget,
      transport,
      preferences,
      lodging,
      lodgingIds,
      List.of(),
      List.of()
    );
  }

  /** 이전 방문 찜만 전달하던 내부 호출도 숙소 찜 없이 사용할 수 있습니다. */
  public TripRequest(
    String title,
    List<String> regions,
    LocalDate startDate,
    LocalDate endDate,
    String origin,
    int partySize,
    int budget,
    String transport,
    List<String> preferences,
    String lodging,
    List<String> lodgingIds,
    List<String> favoriteIds
  ) {
    this(
      title,
      regions,
      startDate,
      endDate,
      origin,
      partySize,
      budget,
      transport,
      preferences,
      lodging,
      lodgingIds,
      favoriteIds,
      List.of()
    );
  }
}
