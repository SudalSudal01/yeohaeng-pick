package kr.yeohaengpick.auth;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** 현재 로그인한 계정만 처리합니다. 요청 본문에서 다른 회원 ID를 받지 않습니다. */
@RestController
@RequestMapping("/api/auth")
public class WithdrawalController {

  private final AuthService auth;
  private final WithdrawalService withdrawal;

  public WithdrawalController(AuthService auth, WithdrawalService withdrawal) {
    this.auth = auth;
    this.withdrawal = withdrawal;
  }

  public record Confirmation(
    @NotBlank @Size(max = 72) String password,
    @NotNull @AssertTrue Boolean confirmed
  ) {}

  @GetMapping("/withdrawal")
  Object preview(Authentication a) {
    return withdrawal.preview(auth.id(a));
  }

  @DeleteMapping("/account")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  void withdraw(
    @Valid @RequestBody Confirmation body,
    Authentication a,
    HttpServletRequest request,
    HttpServletResponse response
  ) {
    withdrawal.withdraw(auth.id(a), body.password);
    // 서비스 트랜잭션 커밋 후 현재 세션을 없앱니다. 다른 기기의 세션은 deleted_at/인증 버전 검사로 거절합니다.
    if (request.getSession(false) != null) request.getSession().invalidate();
    SecurityContextHolder.clearContext();
    for (String name : new String[] { "JSESSIONID", "XSRF-TOKEN" }) {
      Cookie cookie = new Cookie(name, "");
      cookie.setPath("/");
      cookie.setMaxAge(0);
      cookie.setHttpOnly(name.equals("JSESSIONID"));
      cookie.setSecure(request.isSecure());
      response.addCookie(cookie);
    }
  }
}
