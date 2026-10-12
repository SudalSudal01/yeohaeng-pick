package kr.yeohaengpick.trip;

import java.util.Map;
import org.apache.ibatis.annotations.*;

/** 가져오기 중복 방지 기록은 요청한 회원에게만 조회하며 원본 여행 ID나 공유 토큰은 저장하지 않습니다. */
@Mapper
public interface TripImportMapper {
  /** 같은 회원의 가져오기를 직렬화합니다. 서로 다른 공유 링크로 같은 요청 키를 보내도 한 건만 생성됩니다. */
  @Select(
    "SELECT id FROM members WHERE id=#{member} AND deleted_at IS NULL FOR UPDATE"
  )
  String lockMember(String member);

  @Select(
    "SELECT trip_id,payload_hash FROM trip_imports WHERE member_id=#{member} AND request_id=#{request}"
  )
  Map<String, Object> receipt(
    @Param("member") String member,
    @Param("request") String request
  );

  @Insert(
    "INSERT INTO trip_imports(member_id,request_id,trip_id,payload_hash) VALUES(#{member},#{request},#{trip},#{hash})"
  )
  void record(
    @Param("member") String member,
    @Param("request") String request,
    @Param("trip") String trip,
    @Param("hash") String hash
  );
}
