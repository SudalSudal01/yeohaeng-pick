package kr.yeohaengpick.notification;

import jakarta.validation.Valid;
import kr.yeohaengpick.auth.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 세션의 회원 ID만 사용하며 URL이나 요청 본문으로 수신자 ID를 받지 않습니다. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

  private final NotificationService notifications;
  private final AuthService auth;

  public NotificationController(
    NotificationService notifications,
    AuthService auth
  ) {
    this.notifications = notifications;
    this.auth = auth;
  }

  @GetMapping
  NotificationService.Page list(
    @Valid @ModelAttribute NotificationService.Query query,
    Authentication authentication
  ) {
    return notifications.list(query, auth.id(authentication));
  }

  @GetMapping("/unread-count")
  Object unreadCount(Authentication authentication) {
    return notifications.unreadCount(auth.id(authentication));
  }

  @PutMapping("/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void read(@PathVariable String id, Authentication authentication) {
    notifications.read(id, auth.id(authentication));
  }

  @PutMapping("/read-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void readAll(Authentication authentication) {
    notifications.readAll(auth.id(authentication));
  }
}
