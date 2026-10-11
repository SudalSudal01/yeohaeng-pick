package kr.yeohaengpick.transport;

import kr.yeohaengpick.auth.AuthService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** CSRF·세션 보호를 받는 명시적 조회입니다. 검색어/좌표를 URL이나 공유 일정에 남기지 않습니다. */
@RestController
@RequestMapping("/api/trips/{id}/transport")
public class TransportController {

  private final TransportService transport;
  private final AuthService auth;

  public TransportController(TransportService transport, AuthService auth) {
    this.transport = transport;
    this.auth = auth;
  }

  public record Search(String query) {}

  @PostMapping("/origins")
  public Object origins(
    @PathVariable String id,
    @RequestBody Search search,
    Authentication a
  ) {
    return transport.origins(id, auth.id(a), search.query());
  }

  @PostMapping("/routes")
  public Object routes(
    @PathVariable String id,
    @RequestBody TransportService.Request request,
    Authentication a
  ) {
    return transport.routes(id, auth.id(a), request);
  }
}
