package kr.yeohaengpick.trip;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import kr.yeohaengpick.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공개 화이트리스트와 독립된 여행 복사를 한곳에서 관리합니다. 원본의 비공개 상세 응답은 복사 재료로 쓰지 않습니다. */
@Service
public class TripSharingService {

  private final TripMapper trips;
  private final TripImportMapper imports;
  private final JsonCodec json;
  private final Planner planner;

  public TripSharingService(
    TripMapper trips,
    TripImportMapper imports,
    JsonCodec json,
    Planner planner
  ) {
    this.trips = trips;
    this.imports = imports;
    this.json = json;
    this.planner = planner;
  }

  public record ImportRequest(
    @NotNull @Pattern(regexp = "[a-f0-9]{64}") String token,
    @NotNull @Pattern(regexp = "[a-f0-9]{64}") String snapshot,
    @NotNull
    @Pattern(
      regexp = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
    )
    String requestId,
    @NotNull @Valid TripRequest conditions
  ) {}

  /** 조회 중 종료/재발급/편집과 섞인 화면이 만들어지지 않도록 짧은 여행 행 잠금 안에서 공개본을 만듭니다. */
  @Transactional
  public Map<String, Object> shared(String token) {
    return snapshot(publicTrip(token));
  }

  private Map<String, Object> publicTrip(String token) {
    if (token == null || !token.matches("[a-f0-9]{64}")) throw unavailable();
    String id = trips.byShare(token);
    var trip = id == null ? null : trips.lock(id);
    // 토큰을 찾은 다음 잠금을 기다리는 사이 종료됐을 수도 있으므로 반드시 잠금 후 재검사합니다.
    if (
      trip == null || !token.equals(trip.get("shareToken"))
    ) throw unavailable();
    return trip;
  }

  private DomainException unavailable() {
    return new DomainException(
      404,
      "공유가 종료됐거나 유효하지 않은 링크예요."
    );
  }

  private Map<String, Object> snapshot(Map<String, Object> trip) {
    var safe = new LinkedHashMap<String, Object>();
    for (String key : List.of("title", "startDate", "endDate", "transport"))
      safe.put(key, trip.get(key).toString());
    safe.put("regions", json.strings((String) trip.get("regions")));
    safe.put(
      "items",
      trips
        .items((String) trip.get("id"))
        .stream()
        .map(item -> {
          var publicItem = new LinkedHashMap<String, Object>();
          // 장소 ID/출처/참고 활동비는 공개 장소 메타데이터입니다. 직접 입력한 visitCost·메모·예약·고정 여부는 제외합니다.
          for (String key : List.of(
            "dayIndex",
            "sortOrder",
            "placeId",
            "placeName",
            "region",
            "category",
            "startTime",
            "duration",
            "latitude",
            "longitude",
            "placeUrl",
            "indoor",
            "source",
            "cost"
          ))
            publicItem.put(key, item.get(key));
          return publicItem;
        })
        .toList()
    );
    // 비공개 버전/예약 변경은 해시에 넣지 않습니다. 사용자가 본 공개 내용이 바뀌었을 때만 재검토를 요구합니다.
    safe.put("snapshot", hash(json.write(safe)));
    return safe;
  }

  @Transactional
  @SuppressWarnings("unchecked")
  public Map<String, String> importTrip(ImportRequest request, String member) {
    if (imports.lockMember(member) == null) throw new DomainException(
      401,
      "다시 로그인해 주세요."
    );
    String hash = hash(json.write(request));
    var receipt = imports.receipt(member, request.requestId());
    if (receipt != null) {
      if (!hash.equals(receipt.get("payloadHash"))) throw new DomainException(
        409,
        "이미 사용한 가져오기 요청이에요. 새로 열어 다시 시도해 주세요."
      );
      String id = (String) receipt.get("tripId");
      // 복사본 삭제/참여 종료 후 재전송으로 여행이 되살아나거나 권한이 복구되지 않게 합니다.
      if (
        id == null || trips.access(id, member) == 0
      ) throw new DomainException(
        410,
        "이미 가져온 여행이 삭제됐거나 참여가 종료됐어요."
      );
      return Map.of("id", id);
    }
    var original = publicTrip(request.token());
    var view = snapshot(original);
    if (
      !request.snapshot().equals(view.get("snapshot"))
    ) throw new DomainException(
      409,
      "공유 일정이 변경됐어요. 최신 일정을 확인한 뒤 다시 가져와 주세요."
    );
    TripRequest r = request.conditions();
    planner.validate(r);
    long days = ChronoUnit.DAYS.between(
      LocalDate.parse(view.get("startDate").toString()),
      LocalDate.parse(view.get("endDate").toString())
    );
    if (
      !r.regions().equals(view.get("regions")) ||
      ChronoUnit.DAYS.between(r.startDate(), r.endDate()) != days ||
      (r.lodging() != null && !r.lodging().isBlank()) ||
      !r.lodgingIds().isEmpty() ||
      !r.favoriteIds().isEmpty() ||
      !r.favoriteLodgingIds().isEmpty()
    ) throw DomainException.bad(
      "공유 일정의 지역과 여행 일수를 유지해 주세요. 숙소는 가져온 뒤 따로 선택할 수 있어요."
    );
    var items = (List<Map<String, Object>>) view.get("items");
    if (items.isEmpty() || items.size() > 100) throw DomainException.bad(
      "가져올 수 있는 방문 일정이 없거나 너무 많아요."
    );
    String id = UUID.randomUUID().toString();
    var trip = new HashMap<String, Object>();
    trip.put("id", id);
    trip.put("ownerId", member);
    trip.put("title", r.title());
    trip.put("regions", json.write(r.regions()));
    trip.put("startDate", r.startDate());
    trip.put("endDate", r.endDate());
    trip.put("origin", r.origin());
    trip.put("partySize", r.partySize());
    trip.put("budget", r.budget());
    trip.put("transport", r.transport());
    trip.put("preferences", json.write(r.preferences()));
    trip.put("lodging", "");
    trips.insert(trip);
    trips.member(id, member);
    for (var publicItem : items) {
      var item = new HashMap<>(publicItem);
      item.put("id", UUID.randomUUID().toString());
      item.put("tripId", id);
      item.put("fixed", false);
      item.put("bookingStatus", "NONE");
      item.put("bookingReason", "");
      item.put("visitStart", null);
      item.put("visitEnd", null);
      item.put("visitCost", null);
      item.put("visitNote", "");
      trips.item(item);
    }
    // 원본의 준비물·완료 상태도 개인 기록입니다. 복사본에는 공개되지 않은 어떤 목록도 가져오지 않습니다.
    for (String task : List.of(
      "방문일의 운영시간·요금 확인하기",
      "새 날짜의 이동 경로와 숙소 선택하기"
    ))
      trips.addTask(UUID.randomUUID().toString(), id, task);
    imports.record(member, request.requestId(), id, hash);
    return Map.of("id", id);
  }

  private String hash(String value) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          value.getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
