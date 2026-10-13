package kr.yeohaengpick.notification;

import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import kr.yeohaengpick.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱 안에서 확인하는 알림을 생성하고 읽음 상태를 관리합니다.
 * 1차 범위는 동행 투표 생성과 수동 마감이며, 자동 기한 종료는 스케줄러가 없어 알림으로 확정하지 않습니다.
 */
@Service
public class NotificationService {

  public static final String TRIP_POLL_CREATED = "TRIP_POLL_CREATED";
  public static final String TRIP_POLL_CLOSED = "TRIP_POLL_CLOSED";

  private final NotificationMapper notifications;

  public NotificationService(NotificationMapper notifications) {
    this.notifications = notifications;
  }

  /** 쿼리 문자열을 직접 바꿔도 서버가 처리할 수 있는 페이지 범위를 넘지 않게 합니다. */
  public record Query(
    @Min(1) @Max(1000000) Integer page,
    @Min(1) @Max(30) Integer pageSize,
    Boolean unreadOnly
  ) {
    public Query {
      page = page == null ? 1 : page;
      pageSize = pageSize == null ? 20 : pageSize;
      unreadOnly = Boolean.TRUE.equals(unreadOnly);
    }
  }

  /** 회원 식별자는 제외하고 화면 이동과 문구 구성에 필요한 공개 범위의 값만 제공합니다. */
  public record Item(
    String id,
    String type,
    LocalDateTime readAt,
    LocalDateTime createdAt,
    String actorName,
    String tripId,
    String tripTitle,
    String pollId,
    String pollTitle
  ) {}

  public record Page(
    List<Item> items,
    long total,
    int page,
    int pageSize,
    long totalPages,
    long unreadCount
  ) {}

  /** 목록 건수와 페이지가 바뀌면 존재하는 마지막 페이지로 보정해 빈 화면에 갇히지 않게 합니다. */
  @Transactional(readOnly = true)
  public Page list(Query query, String member) {
    // 필터를 건수와 실제 페이지에 똑같이 적용합니다. 전체 목록을 받은 뒤 화면에서 거르면
    // 다음 페이지의 미확인 알림을 놓치므로 DB가 페이지를 나누기 전에 읽음 조건을 검사합니다.
    int unreadOnly = query.unreadOnly() ? 1 : 0;
    long total = notifications.count(member, unreadOnly);
    long totalPages = (total + query.pageSize() - 1) / query.pageSize();
    int page = (int) Math.min(query.page(), Math.max(1, totalPages));
    long offset = (long) (page - 1) * query.pageSize();
    var items =
      total == 0
        ? List.<Item>of()
        : notifications
            .page(member, offset, query.pageSize(), unreadOnly)
            .stream()
            .map(this::item)
            .toList();
    return new Page(
      items,
      total,
      page,
      query.pageSize(),
      totalPages,
      notifications.unread(member)
    );
  }

  @Transactional(readOnly = true)
  public Map<String, Long> unreadCount(String member) {
    return Map.of("unreadCount", notifications.unread(member));
  }

  /** 이미 읽은 알림도 성공하는 멱등 API입니다. 접근할 수 없는 ID는 존재 여부를 숨기며 404로 답합니다. */
  @Transactional
  public void read(String id, String member) {
    if (notifications.read(id, member) != 1) throw new DomainException(
      404,
      "확인할 수 있는 알림을 찾지 못했어요. 목록을 새로고침해 주세요."
    );
  }

  @Transactional
  public void readAll(String member) {
    notifications.readAll(member);
  }

  /**
   * 호출한 투표 작업의 트랜잭션 안에서 현재 동행에게 알림을 만듭니다.
   * 행동자는 제외하며, 한 수신자·투표·종류 조합은 DB 유니크 제약으로 한 번만 저장합니다.
   */
  public void notifyPoll(String trip, String poll, String type, String actor) {
    if (
      !List.of(TRIP_POLL_CREATED, TRIP_POLL_CLOSED).contains(type)
    ) throw new IllegalArgumentException("지원하지 않는 알림 종류");
    for (String recipient : notifications.recipients(trip, actor)) {
      notifications.insert(
        UUID.randomUUID().toString(),
        recipient,
        actor,
        trip,
        poll,
        type
      );
    }
  }

  public void clearTripRecipient(String trip, String member) {
    notifications.clearTripRecipient(trip, member);
  }

  public void clearRecipient(String member) {
    notifications.clearRecipient(member);
  }

  private Item item(Map<String, Object> row) {
    return new Item(
      row.get("id").toString(),
      row.get("eventType").toString(),
      time(row.get("readAt")),
      time(row.get("createdAt")),
      row.get("actorName").toString(),
      row.get("tripId").toString(),
      row.get("tripTitle").toString(),
      row.get("pollId").toString(),
      row.get("pollTitle").toString()
    );
  }

  private LocalDateTime time(Object value) {
    if (value == null) return null;
    if (value instanceof LocalDateTime time) return time;
    if (
      value instanceof Timestamp timestamp
    ) return timestamp.toLocalDateTime();
    return LocalDateTime.parse(value.toString().replace(' ', 'T'));
  }
}
