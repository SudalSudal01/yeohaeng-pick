package kr.yeohaengpick.trip;

import java.util.Map;
import org.apache.ibatis.annotations.*;

/** 성공한 생성 명령의 최소 영수증입니다. 모든 조회는 세션 회원·작업 종류·요청 키로 제한합니다. */
@Mapper
public interface TripMutationMapper {
  /** 초대·공유 가져오기와 동일하게 회원→여행 순서로 잠가 같은 회원의 재시도를 직렬화합니다. */
  @Select(
    "SELECT id FROM members WHERE id=#{member} AND deleted_at IS NULL FOR UPDATE"
  )
  String lockMember(String member);

  @Select(
    "SELECT trip_id,target_id,payload_hash FROM trip_mutations WHERE member_id=#{member} AND operation=#{operation} AND request_id=#{request}"
  )
  Map<String, Object> receipt(
    @Param("member") String member,
    @Param("operation") String operation,
    @Param("request") String request
  );

  @Insert(
    "INSERT INTO trip_mutations(member_id,operation,request_id,payload_hash,trip_id,target_id) VALUES(#{member},#{operation},#{request},#{hash},#{trip},#{target})"
  )
  void record(
    @Param("member") String member,
    @Param("operation") String operation,
    @Param("request") String request,
    @Param("hash") String hash,
    @Param("trip") String trip,
    @Param("target") String target
  );

  @Select(
    "SELECT COUNT(*) FROM trip_tasks WHERE trip_id=#{trip} AND id=#{target}"
  )
  int taskExists(@Param("trip") String trip, @Param("target") String target);

  @Select(
    "SELECT COUNT(*) FROM expenses WHERE trip_id=#{trip} AND id=#{target}"
  )
  int expenseExists(@Param("trip") String trip, @Param("target") String target);
}
