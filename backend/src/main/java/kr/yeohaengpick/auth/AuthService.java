package kr.yeohaengpick.auth;

import jakarta.servlet.http.*;
import java.util.*;
import kr.yeohaengpick.common.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;

/** 회원 생성·로그인의 검증과 세션 저장을 담당합니다. 원문 비밀번호는 어떤 DB/로그에도 기록하지 않습니다. */
@Service
public class AuthService {

  private final MemberMapper members;
  private final PasswordEncoder encoder;
  private final JsonCodec json;
  private final HttpSessionSecurityContextRepository contexts;

  public AuthService(
    MemberMapper m,
    PasswordEncoder p,
    JsonCodec j,
    HttpSessionSecurityContextRepository c
  ) {
    members = m;
    encoder = p;
    json = j;
    contexts = c;
  }

  public Map<String, Object> register(
    String email,
    String password,
    String nickname
  ) {
    email = email.trim().toLowerCase(Locale.ROOT);
    nickname = nickname.trim();
    if (
      !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || email.length() > 254
    ) throw DomainException.bad("올바른 이메일을 입력해 주세요.");
    validatePassword(password);
    if (
      nickname.length() < 2 || nickname.length() > 30
    ) throw DomainException.bad("닉네임은 2~30자로 입력해 주세요.");
    if (members.byEmail(email) != null) throw new DomainException(
      409,
      "이미 가입된 이메일이에요."
    );
    String id = UUID.randomUUID().toString();
    // 운영자 권한은 서버 환경변수에 지정한 이메일에만 부여합니다. 클라이언트 요청의 role은 받지 않습니다.
    members.insert(id, email, encoder.encode(password), nickname, "USER");
    return safe(members.byId(id));
  }

  public void validatePassword(String password) {
    if (
      password == null ||
      password.length() < 10 ||
      password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
    ) throw DomainException.bad(
      "비밀번호는 10자 이상, UTF-8 기준 72바이트 이하로 입력해 주세요."
    );
  }

  public Map<String, Object> login(
    String email,
    String password,
    HttpServletRequest req,
    HttpServletResponse res
  ) {
    var member = members.byEmail(email.trim().toLowerCase(Locale.ROOT));
    if (
      member == null ||
      !encoder.matches(password, (String) member.get("passwordHash"))
    ) throw new DomainException(401, "이메일 또는 비밀번호를 확인해 주세요.");
    if (req.getSession(false) != null) req.changeSessionId();
    else req.getSession(true);
    req
      .getSession()
      .setAttribute("credentialVersion", member.get("credentialVersion"));
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
      new UsernamePasswordAuthenticationToken(
        member.get("id"),
        null,
        List.of(new SimpleGrantedAuthority("ROLE_" + member.get("role")))
      )
    );
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, req, res);
    return safe(member);
  }

  public Map<String, Object> safe(Map<String, Object> source) {
    var result = new HashMap<>(source);
    result.remove("passwordHash");
    result.remove("credentialVersion");
    result.put("preferences", json.strings((String) source.get("preferences")));
    return result;
  }

  public String id(Authentication auth) {
    if (
      auth == null ||
      !auth.isAuthenticated() ||
      "anonymousUser".equals(auth.getName())
    ) throw new DomainException(401, "로그인이 필요해요.");
    return auth.getName();
  }

  public Map<String, Object> me(Authentication auth) {
    return safe(members.byId(id(auth)));
  }
}
