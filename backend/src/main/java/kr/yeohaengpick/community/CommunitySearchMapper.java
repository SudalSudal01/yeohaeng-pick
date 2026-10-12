package kr.yeohaengpick.community;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** 목록에 필요한 공개 필드와 짧은 미리보기만 내려보냅니다. 본문 전체·이메일·신고·삭제 정보는 제외합니다. */
@Mapper
public interface CommunitySearchMapper {
  @SelectProvider(type = CommunitySearchSql.class, method = "count")
  long count(Map<String, Object> params);

  @SelectProvider(type = CommunitySearchSql.class, method = "page")
  List<Map<String, Object>> page(Map<String, Object> params);
}
