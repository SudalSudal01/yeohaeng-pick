package kr.yeohaengpick.transport;

import kr.yeohaengpick.auth.AuthService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 시간 비교와 실제 저장을 분리합니다. 두 POST 모두 여행 멤버 권한·세션·CSRF 검사를 통과해야 합니다. */
@RestController
@RequestMapping("/api/trips/{id}/transport/timing")
public class RouteTimingController {

  private final RouteTimingService service;
  private final AuthService auth;

  public RouteTimingController(RouteTimingService service, AuthService auth) {
    this.service = service;
    this.auth = auth;
  }

  @PostMapping("/preview")
  public Object preview(
    @PathVariable String id,
    @RequestBody RouteTimingService.Request r,
    Authentication a
  ) {
    return service.preview(id, auth.id(a), r);
  }

  @PostMapping("/apply")
  public Object apply(
    @PathVariable String id,
    @RequestBody RouteTimingService.Request r,
    Authentication a
  ) {
    return service.apply(id, auth.id(a), r);
  }

  public record Remove(String itemId, int version) {}

  @PostMapping("/remove")
  public Object remove(
    @PathVariable String id,
    @RequestBody Remove r,
    Authentication a
  ) {
    return service.remove(id, auth.id(a), r);
  }
}
