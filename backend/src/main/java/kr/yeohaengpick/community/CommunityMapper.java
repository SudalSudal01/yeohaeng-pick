package kr.yeohaengpick.community;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** 공개 커뮤니티 쿼리는 이메일·개인 취향·예약 정보에 접근하지 않습니다. */
@Mapper
public interface CommunityMapper {
  @Select(
    "SELECT p.*,m.nickname FROM posts p JOIN members m ON p.author_id=m.id WHERE hidden=FALSE AND deleted_at IS NULL ORDER BY p.created_at DESC LIMIT 100"
  )
  List<Map<String, Object>> posts();

  @Select(
    "SELECT p.*,m.nickname FROM posts p JOIN members m ON p.author_id=m.id WHERE p.id=#{id}"
  )
  Map<String, Object> post(String id);

  /** 수정·상태 변경·댓글·휴지통 이동·운영진 숨김은 같은 게시글을 잠가 완료 순서를 보장합니다. */
  @Select("SELECT * FROM posts WHERE id=#{id} FOR UPDATE")
  Map<String, Object> lockPost(String id);

  /** 휴지통은 로그인한 작성자만 읽습니다. 목록에 본문·댓글을 내려보내지 않습니다. */
  @Select(
    "SELECT id,category,region,title,version,deleted_at,hidden FROM posts WHERE author_id=#{member} AND deleted_at IS NOT NULL ORDER BY deleted_at DESC LIMIT 100"
  )
  List<Map<String, Object>> trash(String member);

  @Update(
    "UPDATE posts SET region=#{region},title=#{title},body=#{body},recruitment_date=#{recruitmentDate},capacity=#{capacity},version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND author_id=#{member} AND version=#{version} AND hidden=FALSE AND deleted_at IS NULL"
  )
  int edit(Map<String, Object> data);

  @Update(
    "UPDATE posts SET deleted_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,version=version+1,status=CASE WHEN category='동행' THEN 'CLOSED' ELSE status END WHERE id=#{id} AND author_id=#{member} AND version=#{version} AND hidden=FALSE AND deleted_at IS NULL"
  )
  int deletePost(
    @Param("id") String id,
    @Param("member") String member,
    @Param("version") int version
  );

  @Update(
    "UPDATE posts SET deleted_at=NULL,updated_at=CURRENT_TIMESTAMP,version=version+1,status=CASE WHEN category='동행' THEN 'CLOSED' ELSE status END WHERE id=#{id} AND author_id=#{member} AND version=#{version} AND hidden=FALSE AND deleted_at IS NOT NULL"
  )
  int restorePost(
    @Param("id") String id,
    @Param("member") String member,
    @Param("version") int version
  );

  @Insert(
    "INSERT INTO posts(id,author_id,category,region,title,body,recruitment_date,capacity) VALUES(#{id},#{authorId},#{category},#{region},#{title},#{body},#{recruitmentDate},#{capacity})"
  )
  void insert(Map<String, Object> p);

  @Select(
    "SELECT c.id,c.author_id,c.body,c.created_at,m.nickname FROM comments c JOIN members m ON c.author_id=m.id WHERE c.post_id=#{id} ORDER BY c.created_at"
  )
  List<Map<String, Object>> comments(String id);

  @Insert(
    "INSERT INTO comments(id,post_id,author_id,body) VALUES(#{id},#{post},#{member},#{body})"
  )
  void comment(
    @Param("id") String id,
    @Param("post") String post,
    @Param("member") String member,
    @Param("body") String body
  );

  @Update(
    "UPDATE posts SET status=#{status},version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND author_id=#{member} AND version=#{version} AND category='동행' AND hidden=FALSE AND deleted_at IS NULL"
  )
  int status(
    @Param("id") String id,
    @Param("member") String member,
    @Param("status") String status,
    @Param("version") int version
  );

  @Select(
    "SELECT COUNT(*) FROM blocks WHERE (blocker_id=#{a} AND blocked_id=#{b}) OR (blocker_id=#{b} AND blocked_id=#{a})"
  )
  int blocked(@Param("a") String a, @Param("b") String b);

  @Insert("INSERT INTO blocks(blocker_id,blocked_id) VALUES(#{a},#{b})")
  void block(@Param("a") String a, @Param("b") String b);

  @Delete("DELETE FROM blocks WHERE blocker_id=#{a} AND blocked_id=#{b}")
  void unblock(@Param("a") String a, @Param("b") String b);

  @Select(
    "SELECT b.blocked_id,m.nickname FROM blocks b JOIN members m ON b.blocked_id=m.id WHERE b.blocker_id=#{id}"
  )
  List<Map<String, Object>> blocks(String id);

  @Insert(
    "INSERT INTO reports(id,reporter_id,target_type,target_id,reason) VALUES(#{id},#{member},#{type},#{target},#{reason})"
  )
  void report(
    @Param("id") String id,
    @Param("member") String member,
    @Param("type") String type,
    @Param("target") String target,
    @Param("reason") String reason
  );

  @Select("SELECT * FROM reports ORDER BY created_at DESC LIMIT 100")
  List<Map<String, Object>> reports();

  @Select("SELECT * FROM reports WHERE id=#{id}")
  Map<String, Object> reportById(String id);

  @Update(
    "UPDATE reports SET status='RESOLVED',resolution=#{resolution} WHERE id=#{id}"
  )
  void resolve(@Param("id") String id, @Param("resolution") String resolution);

  @Update(
    "UPDATE posts SET hidden=#{hidden},version=version+1 WHERE id=#{id} AND hidden<>#{hidden}"
  )
  void hide(@Param("id") String id, @Param("hidden") boolean hidden);

  @Insert(
    "INSERT INTO admin_audit(id,admin_id,action) VALUES(#{id},#{admin},#{action})"
  )
  void audit(
    @Param("id") String id,
    @Param("admin") String admin,
    @Param("action") String action
  );
}
