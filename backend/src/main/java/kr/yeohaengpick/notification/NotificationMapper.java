package kr.yeohaengpick.notification;

import java.util.*;
import org.apache.ibatis.annotations.*;

/**
 * 회원 알림 저장소입니다. 알림을 읽거나 변경하는 SQL은 항상 현재 여행 참여 행을 다시 조인해,
 * 참여가 끝난 회원에게 오래된 행이 남아 있더라도 비공개 여행 정보가 노출되지 않게 합니다.
 */
@Mapper
public interface NotificationMapper {
  /** 여행 행을 잠근 호출자가 사용하며, 그 시점의 현재 참여자만 알림 수신자로 고릅니다. */
  @Select(
    "SELECT member_id FROM trip_members WHERE trip_id=#{trip} AND member_id<>#{actor} ORDER BY member_id"
  )
  List<String> recipients(
    @Param("trip") String trip,
    @Param("actor") String actor
  );

  @Insert(
    "INSERT INTO member_notifications(id,recipient_id,actor_id,trip_id,poll_id,event_type) VALUES(#{id},#{recipient},#{actor},#{trip},#{poll},#{type})"
  )
  void insert(
    @Param("id") String id,
    @Param("recipient") String recipient,
    @Param("actor") String actor,
    @Param("trip") String trip,
    @Param("poll") String poll,
    @Param("type") String type
  );

  @Select(
    "SELECT COUNT(*) FROM member_notifications n JOIN trip_members tm ON tm.trip_id=n.trip_id AND tm.member_id=n.recipient_id WHERE n.recipient_id=#{member} AND (#{unreadOnly}=0 OR n.read_at IS NULL)"
  )
  long count(
    @Param("member") String member,
    @Param("unreadOnly") int unreadOnly
  );

  @Select(
    "SELECT COUNT(*) FROM member_notifications n JOIN trip_members tm ON tm.trip_id=n.trip_id AND tm.member_id=n.recipient_id WHERE n.recipient_id=#{member} AND n.read_at IS NULL"
  )
  long unread(String member);

  /**
   * 목록에는 화면에 필요한 표시 정보만 싣습니다. actor_id와 recipient_id는 선택하지 않아
   * 매퍼 결과가 실수로 그대로 반환돼도 내부 UUID나 이메일이 API에 포함되지 않습니다.
   */
  @Select(
    "SELECT n.id,n.event_type,n.read_at,n.created_at,a.nickname AS actor_name,n.trip_id,t.title AS trip_title,n.poll_id,p.title AS poll_title FROM member_notifications n JOIN trip_members tm ON tm.trip_id=n.trip_id AND tm.member_id=n.recipient_id JOIN members a ON a.id=n.actor_id JOIN trips t ON t.id=n.trip_id JOIN trip_polls p ON p.id=n.poll_id WHERE n.recipient_id=#{member} AND (#{unreadOnly}=0 OR n.read_at IS NULL) ORDER BY n.created_at DESC,n.id DESC OFFSET #{offset} ROWS FETCH NEXT #{pageSize} ROWS ONLY"
  )
  List<Map<String, Object>> page(
    @Param("member") String member,
    @Param("offset") long offset,
    @Param("pageSize") int pageSize,
    @Param("unreadOnly") int unreadOnly
  );

  /** 다른 회원의 알림 ID를 알아도 수신자와 현재 여행 참여 조건이 모두 맞아야 읽음 처리됩니다. */
  @Update(
    "UPDATE member_notifications SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP) WHERE id=#{id} AND recipient_id=#{member} AND EXISTS (SELECT 1 FROM trip_members tm WHERE tm.trip_id=member_notifications.trip_id AND tm.member_id=#{member})"
  )
  int read(@Param("id") String id, @Param("member") String member);

  @Update(
    "UPDATE member_notifications SET read_at=CURRENT_TIMESTAMP WHERE recipient_id=#{member} AND read_at IS NULL AND EXISTS (SELECT 1 FROM trip_members tm WHERE tm.trip_id=member_notifications.trip_id AND tm.member_id=#{member})"
  )
  int readAll(String member);

  /** 참여 종료 뒤 재초대되어도 예전 알림이 새 알림처럼 다시 보이지 않도록 수신 행을 지웁니다. */
  @Delete(
    "DELETE FROM member_notifications WHERE trip_id=#{trip} AND recipient_id=#{member}"
  )
  void clearTripRecipient(
    @Param("trip") String trip,
    @Param("member") String member
  );

  /** 회원 탈퇴에서는 그 회원이 받은 비공개 알림 전체를 계정 익명화 전에 제거합니다. */
  @Delete("DELETE FROM member_notifications WHERE recipient_id=#{member}")
  void clearRecipient(String member);
}
