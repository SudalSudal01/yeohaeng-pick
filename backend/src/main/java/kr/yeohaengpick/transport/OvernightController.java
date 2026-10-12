package kr.yeohaengpick.transport;

import kr.yeohaengpick.auth.AuthService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 미리보기와 명시적 저장을 나눕니다. 기존 경로 해제 API로 숙소 연결도 해제할 수 있습니다. */
@RestController
@RequestMapping("/api/trips/{id}/transport/overnight")
public class OvernightController {

  private final OvernightService service;
  private final AuthService auth;

  public OvernightController(OvernightService service, AuthService auth) {
    this.service = service;
    this.auth = auth;
  }

  @PostMapping("/preview")
  public Object preview(
    @PathVariable String id,
    @RequestBody OvernightService.Request r,
    Authentication a
  ) {
    return service.preview(id, auth.id(a), r);
  }

  @PostMapping("/apply")
  public Object apply(
    @PathVariable String id,
    @RequestBody OvernightService.Request r,
    Authentication a
  ) {
    return service.apply(id, auth.id(a), r);
  }
}
