package kr.yeohaengpick.collaboration;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** 초대 응답을 조건부 UPDATE로 처리해 중복 수락이 참여자를 중복 생성하지 않게 합니다. */
@Mapper
public interface InvitationMapper {
  @Select(
    "SELECT i.*,t.title,m.nickname FROM invitations i JOIN trips t ON i.trip_id=t.id JOIN members m ON m.id=i.sender_id WHERE i.recipient_id=#{member} AND i.status='PENDING' ORDER BY i.created_at DESC"
  )
  List<Map<String, Object>> list(String member);

  @Select("SELECT * FROM invitations WHERE id=#{id}")
  Map<String, Object> get(String id);

  @Select(
    "SELECT COUNT(*) FROM invitations WHERE trip_id=#{trip} AND recipient_id=#{recipient} AND status='PENDING'"
  )
  int pending(@Param("trip") String trip, @Param("recipient") String recipient);

  @Insert(
    "INSERT INTO invitations(id,trip_id,sender_id,recipient_id) VALUES(#{id},#{trip},#{sender},#{recipient})"
  )
  void insert(
    @Param("id") String id,
    @Param("trip") String trip,
    @Param("sender") String sender,
    @Param("recipient") String recipient
  );

  @Update(
    "UPDATE invitations SET status=#{status} WHERE id=#{id} AND recipient_id=#{member} AND status='PENDING'"
  )
  int respond(
    @Param("id") String id,
    @Param("member") String member,
    @Param("status") String status
  );

  @Update(
    "UPDATE trips SET preferences=#{preferences},version=version+1 WHERE id=#{trip}"
  )
  void preferences(
    @Param("trip") String trip,
    @Param("preferences") String preferences
  );
}
