package kr.yeohaengpick.community;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** 신고 증거는 접수 시 한 번만 쓰며, 처리 쿼리에는 증거를 바꾸는 열을 포함하지 않습니다. */
@Mapper
public interface ReportMapper {
  @Insert(
    "INSERT INTO reports(id,reporter_id,target_type,target_id,reason,evidence,captured_at) VALUES(#{id},#{member},#{type},#{target},#{reason},#{evidence,jdbcType=CLOB},CURRENT_TIMESTAMP)"
  )
  void insert(Map<String, Object> report);

  @Select(
    "SELECT * FROM reports ORDER BY created_at DESC,id DESC FETCH FIRST 100 ROWS ONLY"
  )
  List<Map<String, Object>> list();

  /** 두 관리자의 동시 처리도 한 건만 성공하도록 신고 행부터 잠급니다. */
  @Select("SELECT * FROM reports WHERE id=#{id} FOR UPDATE")
  Map<String, Object> lock(String id);

  @Update(
    "UPDATE reports SET status='RESOLVED',resolution=#{resolution},resolved_at=CURRENT_TIMESTAMP,resolved_by=#{admin} WHERE id=#{id} AND status='OPEN'"
  )
  int resolve(
    @Param("id") String id,
    @Param("resolution") String resolution,
    @Param("admin") String admin
  );

  @Insert(
    "INSERT INTO admin_audit(id,admin_id,action) VALUES(#{id},#{admin},#{action})"
  )
  void audit(
    @Param("id") String id,
    @Param("admin") String admin,
    @Param("action") String action
  );

  /** 회원 신고는 공개 식별 정보만 조회합니다. 이메일·비밀번호·취향을 읽거나 복제하지 않습니다. */
  @Select(
    "SELECT id,nickname FROM members WHERE id=#{id} AND deleted_at IS NULL"
  )
  Map<String, Object> publicMember(String id);
}
