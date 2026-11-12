package kr.yeohaengpick.messaging;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** 개인 대화는 양 당사자, 여행 대화는 현재 여행 참여자만 조회하도록 서비스에서 검증합니다. */
@Mapper
public interface MessageMapper {
  /** 검색·정확 조회에 같은 공개 조건을 적용합니다. 개인정보 컬럼은 SELECT에 포함하지 않습니다. */
  String ELIGIBLE_RECIPIENT =
    " m.id<>#{member} AND m.deleted_at IS NULL AND m.dm_scope IN ('ALL','COMPANIONS') " +
    "AND NOT EXISTS (SELECT 1 FROM blocks bl WHERE (bl.blocker_id=#{member} AND bl.blocked_id=m.id) OR (bl.blocker_id=m.id AND bl.blocked_id=#{member})) " +
    "AND (m.dm_scope='ALL' OR EXISTS (SELECT 1 FROM trip_members mine JOIN trip_members theirs ON mine.trip_id=theirs.trip_id WHERE mine.member_id=#{member} AND theirs.member_id=m.id)) ";

  @Select(
    "SELECT m.id,m.nickname FROM members m WHERE " +
      ELIGIBLE_RECIPIENT +
      "AND LOWER(m.nickname) LIKE #{pattern} ESCAPE '!' ORDER BY m.nickname,m.id FETCH FIRST 21 ROWS ONLY"
  )
  List<Map<String, Object>> recipients(
    @Param("member") String member,
    @Param("pattern") String pattern
  );

  @Select(
    "SELECT m.id,m.nickname FROM members m WHERE " +
      ELIGIBLE_RECIPIENT +
      "AND m.id=#{other}"
  )
  Map<String, Object> recipient(
    @Param("member") String member,
    @Param("other") String other
  );

  /** 두 회원을 항상 ID 정렬 순서로 잠가 개인 대화 생성·차단·전송의 완료 순서를 맞춥니다. */
  @Select("SELECT id FROM members WHERE id=#{id} FOR UPDATE")
  String lockMember(String id);

  @Select(
    "SELECT c.*,a.nickname AS requester_name,b.nickname AS recipient_name,t.title AS trip_title FROM conversations c JOIN members a ON a.id=c.requester_id JOIN members b ON b.id=c.recipient_id LEFT JOIN trips t ON t.id=c.trip_id WHERE (c.trip_id IS NULL AND (c.requester_id=#{id} OR c.recipient_id=#{id})) OR c.trip_id IN (SELECT trip_id FROM trip_members WHERE member_id=#{id}) ORDER BY c.created_at DESC,c.id DESC"
  )
  List<Map<String, Object>> list(String id);

  @Select(
    "SELECT c.*,a.nickname AS requester_name,b.nickname AS recipient_name,t.title AS trip_title FROM conversations c JOIN members a ON a.id=c.requester_id JOIN members b ON b.id=c.recipient_id LEFT JOIN trips t ON t.id=c.trip_id WHERE c.id=#{id}"
  )
  Map<String, Object> room(String id);

  @Select(
    "SELECT * FROM conversations WHERE trip_id=#{trip} FETCH FIRST 1 ROWS ONLY"
  )
  Map<String, Object> group(String trip);

  @Select(
    "SELECT * FROM conversations WHERE trip_id IS NULL AND ((requester_id=#{a} AND recipient_id=#{b}) OR (requester_id=#{b} AND recipient_id=#{a})) AND status <> 'DECLINED' FETCH FIRST 1 ROWS ONLY"
  )
  Map<String, Object> existing(@Param("a") String a, @Param("b") String b);

  @Insert(
    "INSERT INTO conversations(id,requester_id,recipient_id,status,trip_id) VALUES(#{id},#{a},#{b},#{status},#{trip})"
  )
  void create(
    @Param("id") String id,
    @Param("a") String a,
    @Param("b") String b,
    @Param("status") String status,
    @Param("trip") String trip
  );

  /** 최신 50개와 더 있는지 판정할 한 행만 읽습니다. ID를 보조 정렬키로 사용해 같은 시각도 빠지지 않습니다. */
  @Select(
    "SELECT msg.*,m.nickname FROM messages msg JOIN members m ON m.id=msg.sender_id WHERE msg.conversation_id=#{id} ORDER BY msg.created_at DESC,msg.id DESC FETCH FIRST 51 ROWS ONLY"
  )
  List<Map<String, Object>> latestMessages(String id);

  /** cursor가 속한 방 검사는 서비스가 먼저 수행합니다. 시각 변환 없이 DB의 원본 TIMESTAMP를 비교합니다. */
  @Select(
    "SELECT msg.*,m.nickname FROM messages msg JOIN members m ON m.id=msg.sender_id WHERE msg.conversation_id=#{id} AND (msg.created_at < (SELECT created_at FROM messages WHERE id=#{before} AND conversation_id=#{id}) OR (msg.created_at = (SELECT created_at FROM messages WHERE id=#{before} AND conversation_id=#{id}) AND msg.id < #{before})) ORDER BY msg.created_at DESC,msg.id DESC FETCH FIRST 51 ROWS ONLY"
  )
  List<Map<String, Object>> olderMessages(
    @Param("id") String id,
    @Param("before") String before
  );

  @Insert(
    "INSERT INTO messages(id,conversation_id,sender_id,body) VALUES(#{id},#{room},#{sender},#{body,jdbcType=CLOB})"
  )
  void send(
    @Param("id") String id,
    @Param("room") String room,
    @Param("sender") String sender,
    @Param("body") String body
  );

  @Update(
    "UPDATE conversations SET status=#{status} WHERE id=#{id} AND recipient_id=#{member} AND status='PENDING'"
  )
  int respond(
    @Param("id") String id,
    @Param("member") String member,
    @Param("status") String status
  );

  @Select("SELECT * FROM messages WHERE id=#{id}")
  Map<String, Object> message(String id);

  @Select(
    "SELECT COUNT(*) FROM trip_members a JOIN trip_members b ON a.trip_id=b.trip_id WHERE a.member_id=#{a} AND b.member_id=#{b}"
  )
  int companions(@Param("a") String a, @Param("b") String b);
}
