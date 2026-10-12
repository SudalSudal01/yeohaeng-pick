package kr.yeohaengpick.collaboration;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** 소유권 변경과 관리 기록만 다룹니다. 참여자 이메일이나 외부 예약의 계약자를 변경하지 않습니다. */
@Mapper
public interface TripOwnershipMapper {
  @Update(
    "UPDATE trips SET owner_id=#{target},version=version+1 WHERE id=#{trip} AND owner_id=#{owner} AND version=#{version}"
  )
  int transfer(
    @Param("trip") String trip,
    @Param("owner") String owner,
    @Param("target") String target,
    @Param("version") int version
  );

  /** 이전 소유자의 대기 초대는 새로운 관리자의 승인 없이 뒤늦게 접근 권한을 만들지 못하게 합니다. */
  @Update(
    "UPDATE invitations SET status='REVOKED' WHERE trip_id=#{trip} AND status='PENDING'"
  )
  int revokePending(String trip);

  @Insert(
    "INSERT INTO trip_ownership_history(id,trip_id,previous_owner_id,new_owner_id,version,revoked_invitations) VALUES(#{id},#{trip},#{owner},#{target},#{version},#{revoked})"
  )
  void record(
    @Param("id") String id,
    @Param("trip") String trip,
    @Param("owner") String owner,
    @Param("target") String target,
    @Param("version") int version,
    @Param("revoked") int revoked
  );

  /** 현재 참여자에게 최근 변경 20건만 반환합니다. 공개 공유 데이터에는 이 기록을 넣지 않습니다. */
  @Select(
    "SELECT h.id,h.previous_owner_id,h.new_owner_id,h.version,h.revoked_invitations,h.created_at,p.nickname AS previous_owner_name,n.nickname AS new_owner_name FROM trip_ownership_history h JOIN members p ON p.id=h.previous_owner_id JOIN members n ON n.id=h.new_owner_id WHERE h.trip_id=#{trip} ORDER BY h.version DESC FETCH FIRST 20 ROWS ONLY"
  )
  List<Map<String, Object>> history(String trip);
}
