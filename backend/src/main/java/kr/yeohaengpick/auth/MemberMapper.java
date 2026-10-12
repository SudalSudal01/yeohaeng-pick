package kr.yeohaengpick.auth;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** 인증 정보는 이 매퍼에서만 읽습니다. 사용자에게 반환할 때 passwordHash를 제거합니다. */
@Mapper
public interface MemberMapper {
  /** 공동 기록 연결용 탈퇴 행과 실제 활동 가능한 계정을 구분합니다. */
  static boolean active(Map<String, Object> member) {
    return member != null && member.get("deletedAt") == null;
  }

  @Select("SELECT * FROM members WHERE email=#{email}")
  Map<String, Object> byEmail(String email);

  @Select("SELECT * FROM members WHERE id=#{id}")
  Map<String, Object> byId(String id);

  @Insert(
    "INSERT INTO members(id,email,password_hash,nickname,role) VALUES(#{id},#{email},#{password},#{nickname},#{role})"
  )
  void insert(
    @Param("id") String id,
    @Param("email") String email,
    @Param("password") String password,
    @Param("nickname") String nickname,
    @Param("role") String role
  );

  @Update(
    "UPDATE members SET nickname=#{nickname}, preferences=#{preferences}, dm_scope=#{scope} WHERE id=#{id}"
  )
  void update(
    @Param("id") String id,
    @Param("nickname") String nickname,
    @Param("preferences") String preferences,
    @Param("scope") String scope
  );

  @Insert(
    "INSERT INTO email_tokens(token_hash,member_id,purpose,expires_at) VALUES(#{hash},#{memberId},#{purpose},#{expires})"
  )
  void token(
    @Param("hash") String hash,
    @Param("memberId") String memberId,
    @Param("purpose") String purpose,
    @Param("expires") java.time.LocalDateTime expires
  );

  @Select(
    "SELECT * FROM email_tokens WHERE token_hash=#{hash} AND expires_at > CURRENT_TIMESTAMP"
  )
  Map<String, Object> tokenByHash(String hash);

  @Delete("DELETE FROM email_tokens WHERE token_hash=#{hash}")
  void deleteToken(String hash);

  @Delete(
    "DELETE FROM email_tokens WHERE token_hash=#{hash} AND expires_at > CURRENT_TIMESTAMP"
  )
  int consumeToken(String hash);

  @Delete(
    "DELETE FROM email_tokens WHERE member_id=#{member} AND purpose=#{purpose}"
  )
  void invalidateTokens(
    @Param("member") String member,
    @Param("purpose") String purpose
  );

  @Update("UPDATE members SET role='ADMIN' WHERE id=#{id} AND verified=TRUE")
  void promote(String id);

  @Update("UPDATE members SET verified=TRUE WHERE id=#{id}")
  void verify(String id);

  @Update(
    "UPDATE members SET password_hash=#{hash},credential_version=credential_version+1 WHERE id=#{id}"
  )
  void password(@Param("id") String id, @Param("hash") String hash);
}
