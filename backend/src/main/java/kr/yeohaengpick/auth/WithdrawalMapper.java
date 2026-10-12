package kr.yeohaengpick.auth;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** 탈퇴 시 개인 계정과 공개 작성물을 정리합니다. 공동 기록의 작성자 ID는 외래키 연결용으로만 남깁니다. */
@Mapper
public interface WithdrawalMapper {
  @Select(
    "SELECT t.id,t.title,(SELECT COUNT(*) FROM trip_members tm WHERE tm.trip_id=t.id) AS member_count FROM trips t WHERE t.owner_id=#{member} ORDER BY t.created_at DESC"
  )
  List<Map<String, Object>> owned(String member);

  @Select("SELECT COUNT(*) FROM trip_members WHERE member_id=#{member}")
  int participation(String member);

  /** 여러 여행에 동시에 참여해도 일관된 ID 순서로 잠급니다. 소유권 이전/나가기와 같은 여행 잠금입니다. */
  @Select(
    "SELECT * FROM trips WHERE owner_id=#{member} OR id IN (SELECT trip_id FROM trip_members WHERE member_id=#{member}) ORDER BY id FOR UPDATE"
  )
  List<Map<String, Object>> lockTrips(String member);

  @Update("UPDATE trips SET version=version+1 WHERE id=#{trip}")
  void bump(String trip);

  @Delete("DELETE FROM trip_members WHERE member_id=#{member}")
  void leaveAll(String member);

  @Delete(
    "DELETE FROM invitations WHERE sender_id=#{member} OR recipient_id=#{member}"
  )
  void invitations(String member);

  @Delete(
    "DELETE FROM blocks WHERE blocker_id=#{member} OR blocked_id=#{member}"
  )
  void blocks(String member);

  @Delete("DELETE FROM email_tokens WHERE member_id=#{member}")
  void tokens(String member);

  /** 가져오기 요청의 중복 방지 기록도 개인 계정 정리와 함께 제거합니다. 타인이 만든 독립 복사본은 유지합니다. */
  @Delete("DELETE FROM trip_imports WHERE member_id=#{member}")
  void imports(String member);

  /** 비공개 장소 찜은 탈퇴한 회원 행에 남기지 않습니다. */
  @Delete("DELETE FROM favorite_places WHERE member_id=#{member}")
  void favorites(String member);

  @Update(
    "UPDATE conversations SET status='CLOSED' WHERE trip_id IS NULL AND (requester_id=#{member} OR recipient_id=#{member})"
  )
  void closeDirectRooms(String member);

  @Update(
    "UPDATE posts SET title='탈퇴한 회원의 게시글',body='',region='',recruitment_date=NULL,capacity=NULL,status='CLOSED',deleted_at=COALESCE(deleted_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE author_id=#{member}"
  )
  void erasePosts(String member);

  @Update(
    "UPDATE comments SET body='',deleted_at=COALESCE(deleted_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE author_id=#{member}"
  )
  void eraseComments(String member);

  // Oracle 19c는 SQL BOOLEAN 대신 CHECK로 제한한 NUMBER(1)을 사용합니다.
  @Update(
    value = "UPDATE members SET email=#{replacement},password_hash='!withdrawn',nickname='탈퇴한 회원',role='USER',verified=0,preferences='[]',dm_scope='OFF',credential_version=credential_version+1,deleted_at=CURRENT_TIMESTAMP WHERE id=#{member} AND deleted_at IS NULL",
    databaseId = "oracle"
  )
  @Update(
    "UPDATE members SET email=#{replacement},password_hash='!withdrawn',nickname='탈퇴한 회원',role='USER',verified=FALSE,preferences='[]',dm_scope='OFF',credential_version=credential_version+1,deleted_at=CURRENT_TIMESTAMP WHERE id=#{member} AND deleted_at IS NULL"
  )
  int anonymize(
    @Param("member") String member,
    @Param("replacement") String replacement
  );
}
