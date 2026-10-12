package kr.yeohaengpick.favorite;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** 찜은 공동 여행과 분리한 회원 개인 기록입니다. 모든 조회·삭제 SQL에 로그인 회원 조건을 함께 바인딩합니다. */
@Mapper
public interface FavoriteMapper {
  // 같은 회원의 저장/해제를 직렬화해 중복 클릭과 최대 보관 개수 검사 사이의 경쟁을 막습니다.
  @Select(
    "SELECT id FROM members WHERE id=#{member} AND deleted_at IS NULL FOR UPDATE"
  )
  String lockMember(String member);

  @Select(
    "SELECT place_id,place_json,created_at FROM favorite_places WHERE member_id=#{member} ORDER BY created_at DESC,place_id"
  )
  List<Map<String, Object>> list(String member);

  @Select(
    "SELECT place_id,place_json,created_at FROM favorite_places WHERE member_id=#{member} AND place_id=#{place}"
  )
  Map<String, Object> get(
    @Param("member") String member,
    @Param("place") String place
  );

  @Select("SELECT COUNT(*) FROM favorite_places WHERE member_id=#{member}")
  int count(String member);

  @Insert(
    "INSERT INTO favorite_places(member_id,place_id,place_json) VALUES(#{member},#{place},#{snapshot,jdbcType=CLOB})"
  )
  void insert(
    @Param("member") String member,
    @Param("place") String place,
    @Param("snapshot") String snapshot
  );

  @Delete(
    "DELETE FROM favorite_places WHERE member_id=#{member} AND place_id=#{place}"
  )
  void delete(@Param("member") String member, @Param("place") String place);
}
