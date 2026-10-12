package kr.yeohaengpick.messaging;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** 개인 대화는 양 당사자, 여행 대화는 현재 여행 참여자만 조회하도록 서비스에서 검증합니다. */
@Mapper
public interface MessageMapper {
  @Select(
    "SELECT c.*,a.nickname AS requester_name,b.nickname AS recipient_name FROM conversations c JOIN members a ON a.id=c.requester_id JOIN members b ON b.id=c.recipient_id WHERE (c.trip_id IS NULL AND (c.requester_id=#{id} OR c.recipient_id=#{id})) OR c.trip_id IN (SELECT trip_id FROM trip_members WHERE member_id=#{id}) ORDER BY c.created_at DESC"
  )
  List<Map<String, Object>> list(String id);

  @Select("SELECT * FROM conversations WHERE id=#{id}")
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

  @Select(
    "SELECT msg.*,m.nickname FROM messages msg JOIN members m ON m.id=msg.sender_id WHERE conversation_id=#{id} ORDER BY msg.created_at FETCH FIRST 300 ROWS ONLY"
  )
  List<Map<String, Object>> messages(String id);

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
