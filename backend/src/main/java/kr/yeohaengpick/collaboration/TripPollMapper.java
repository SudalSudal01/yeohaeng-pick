package kr.yeohaengpick.collaboration;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

/**
 * 여행 투표의 질문·후보·표만 관리합니다.
 * 모든 SQL은 여행 ID와 투표 ID를 함께 받아 다른 여행의 객체를 URL로 섞어 접근하지 못하게 합니다.
 */
@Mapper
public interface TripPollMapper {
  @Select("SELECT COUNT(*) FROM trip_polls WHERE trip_id=#{trip}")
  int count(String trip);

  @Insert(
    "INSERT INTO trip_polls(id,trip_id,created_by,title,selection_type,closes_at) VALUES(#{id},#{trip},#{member},#{title},#{selectionType},#{closesAt,jdbcType=TIMESTAMP})"
  )
  void insert(
    @Param("id") String id,
    @Param("trip") String trip,
    @Param("member") String member,
    @Param("title") String title,
    @Param("selectionType") String selectionType,
    @Param("closesAt") LocalDateTime closesAt
  );

  @Insert(
    "INSERT INTO trip_poll_options(poll_id,id,label,sort_order) VALUES(#{poll},#{id},#{label},#{sortOrder})"
  )
  void option(
    @Param("poll") String poll,
    @Param("id") String id,
    @Param("label") String label,
    @Param("sortOrder") int sortOrder
  );

  /** 목록은 여행당 생성 상한과 같은 50건으로 제한하며, 표와 후보는 아래 묶음 조회로 합칩니다. */
  @Select(
    "SELECT p.id,p.trip_id,p.created_by,p.title,p.selection_type,p.status,p.closes_at,p.closed_at,p.version,p.created_at,m.nickname AS creator_name FROM trip_polls p JOIN members m ON m.id=p.created_by WHERE p.trip_id=#{trip} ORDER BY p.created_at DESC,p.id FETCH FIRST 50 ROWS ONLY"
  )
  List<Map<String, Object>> polls(String trip);

  /** 쓰기 작업은 여행 행 다음에 투표 행을 잠가 참여 해제·마감·삭제와 같은 순서로 직렬화합니다. */
  @Select(
    "SELECT * FROM trip_polls WHERE trip_id=#{trip} AND id=#{poll} FOR UPDATE"
  )
  Map<String, Object> lock(
    @Param("trip") String trip,
    @Param("poll") String poll
  );

  @Select(
    "SELECT o.poll_id,o.id,o.label,o.sort_order FROM trip_poll_options o JOIN trip_polls p ON p.id=o.poll_id WHERE p.trip_id=#{trip} ORDER BY o.poll_id,o.sort_order"
  )
  List<Map<String, Object>> optionsForTrip(String trip);

  @Select(
    "SELECT id,poll_id,label,sort_order FROM trip_poll_options WHERE poll_id=#{poll} ORDER BY sort_order"
  )
  List<Map<String, Object>> options(String poll);

  /**
   * 집계에서도 현재 참여 행을 다시 조인합니다. 정상 흐름에서는 참여 종료 때 표를 지우지만,
   * 오래된 데이터가 남더라도 현재 동행의 결정에 포함하지 않는 방어선입니다.
   */
  @Select(
    "SELECT v.poll_id,v.option_id,v.member_id FROM trip_poll_votes v JOIN trip_polls p ON p.id=v.poll_id JOIN trip_members tm ON tm.trip_id=p.trip_id AND tm.member_id=v.member_id WHERE p.trip_id=#{trip}"
  )
  List<Map<String, Object>> activeVotes(String trip);

  @Select("SELECT COUNT(*) FROM trip_members WHERE trip_id=#{trip}")
  int eligibleMembers(String trip);

  /**
   * 후보 검증이 끝난 직후에도 한국 현지 시각 기준으로 열려 있는지 다시 확인합니다.
   * DB 세션 시간대가 달라도 애플리케이션의 자동 마감 판단과 같은 결과를 내도록 기준 시각을 바인딩합니다.
   */
  @Select(
    "SELECT COUNT(*) FROM trip_polls WHERE trip_id=#{trip} AND id=#{poll} AND status='OPEN' AND (closes_at IS NULL OR closes_at>#{now,jdbcType=TIMESTAMP})"
  )
  int open(
    @Param("trip") String trip,
    @Param("poll") String poll,
    @Param("now") LocalDateTime now
  );

  @Delete(
    "DELETE FROM trip_poll_votes WHERE poll_id=#{poll} AND member_id=#{member}"
  )
  void clearBallot(@Param("poll") String poll, @Param("member") String member);

  @Insert(
    "INSERT INTO trip_poll_votes(poll_id,option_id,member_id) VALUES(#{poll},#{option},#{member})"
  )
  void vote(
    @Param("poll") String poll,
    @Param("option") String option,
    @Param("member") String member
  );

  @Update(
    "UPDATE trip_polls SET status='CLOSED',closed_at=#{now,jdbcType=TIMESTAMP},version=version+1 WHERE trip_id=#{trip} AND id=#{poll} AND version=#{version} AND status='OPEN' AND (closes_at IS NULL OR closes_at>#{now,jdbcType=TIMESTAMP})"
  )
  int close(
    @Param("trip") String trip,
    @Param("poll") String poll,
    @Param("version") int version,
    @Param("now") LocalDateTime now
  );

  @Delete(
    "DELETE FROM trip_polls WHERE trip_id=#{trip} AND id=#{poll} AND version=#{version}"
  )
  int delete(
    @Param("trip") String trip,
    @Param("poll") String poll,
    @Param("version") int version
  );

  /** 나가기나 참여 해제 뒤 같은 초대를 다시 받아도 과거 선택이 되살아나지 않게 합니다. */
  @Delete(
    "DELETE FROM trip_poll_votes WHERE member_id=#{member} AND poll_id IN (SELECT id FROM trip_polls WHERE trip_id=#{trip})"
  )
  void clearMemberVotes(
    @Param("trip") String trip,
    @Param("member") String member
  );
}
