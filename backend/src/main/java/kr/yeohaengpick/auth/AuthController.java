package kr.yeohaengpick.auth;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import kr.yeohaengpick.common.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** 이메일 로그인 API. 읽기 전용 CSRF 발급을 먼저 호출해야 상태 변경 요청을 보낼 수 있습니다. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthService auth;
  private final MemberMapper members;
  private final JsonCodec json;
  private final EmailAccountService email;

  public AuthController(
    AuthService a,
    MemberMapper m,
    JsonCodec j,
    EmailAccountService e
  ) {
    auth = a;
    members = m;
    json = j;
    email = e;
  }

  public record Email(
    @NotBlank @jakarta.validation.constraints.Email String email
  ) {}

  public record Token(@NotBlank String token, String password) {}

  @PostMapping("/verify/send")
  Object sendVerification(@Valid @RequestBody Email e, Authentication a) {
    var me = auth.me(a);
    email.send((String) me.get("email"), "VERIFY");
    return Map.of("message", "인증 안내를 전송했어요.");
  }

  @PostMapping("/verify")
  Object verify(@Valid @RequestBody Token t) {
    email.consume(t.token, "VERIFY", null);
    return Map.of("message", "이메일 인증을 완료했어요. 다시 로그인해 주세요.");
  }

  @PostMapping("/reset/send")
  Object sendReset(@Valid @RequestBody Email e) {
    email.send(e.email, "RESET");
    return Map.of("message", "가입된 이메일이면 재설정 안내를 전송했어요.");
  }

  @PostMapping("/reset")
  Object reset(@Valid @RequestBody Token t, HttpServletRequest request) {
    email.consume(t.token, "RESET", t.password);
    if (request.getSession(false) != null) request.getSession().invalidate();
    return Map.of("message", "비밀번호를 변경했어요. 다시 로그인해 주세요.");
  }

  public record Credentials(
    @NotBlank String email,
    @NotBlank String password
  ) {}

  public record Registration(
    @NotBlank String email,
    @NotBlank String password,
    @NotBlank String nickname
  ) {}

  public record Profile(
    @Size(min = 2, max = 30) String nickname,
    List<@Size(max = 30) String> preferences,
    @NotBlank String dmScope
  ) {}

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "header", token.getHeaderName());
  }

  @PostMapping("/register")
  public Object register(@Valid @RequestBody Registration r) {
    return auth.register(r.email, r.password, r.nickname);
  }

  @PostMapping("/login")
  public Object login(
    @Valid @RequestBody Credentials c,
    HttpServletRequest req,
    HttpServletResponse res
  ) {
    return auth.login(c.email, c.password, req, res);
  }

  @GetMapping("/me")
  public Object me(Authentication a) {
    return auth.me(a);
  }

  @PutMapping("/profile")
  public Object profile(@Valid @RequestBody Profile p, Authentication a) {
    if (
      !List.of("ALL", "COMPANIONS", "OFF").contains(p.dmScope) ||
      p.preferences == null ||
      p.preferences.size() > 12
    ) throw DomainException.bad("설정 값을 확인해 주세요.");
    members.update(
      auth.id(a),
      p.nickname,
      json.write(p.preferences),
      p.dmScope
    );
    return auth.me(a);
  }
}
