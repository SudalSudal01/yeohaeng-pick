package kr.yeohaengpick.messaging;

import kr.yeohaengpick.auth.AuthService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 요청함과 일반 대화의 상태를 분리한 메시지 API. 화면은 주기적으로 새 메시지를 동기화합니다. */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

  private final MessageService messages;
  private final AuthService auth;

  public MessageController(MessageService m, AuthService a) {
    messages = m;
    auth = a;
  }

  public record Request(String email, String recipientId, String body) {}

  public record Text(String body) {}

  public record Response(boolean accept) {}

  public record Block(boolean enabled) {}

  @GetMapping
  Object list(Authentication a) {
    return messages.list(auth.id(a));
  }

  @PostMapping
  Object request(@RequestBody Request r, Authentication a) {
    return messages.request(r.email, r.recipientId, r.body, auth.id(a));
  }

  @GetMapping("/blocks")
  Object blocks(Authentication a) {
    return messages.blocks(auth.id(a));
  }

  @PutMapping("/blocks/{member}")
  Object block(
    @PathVariable String member,
    @RequestBody Block b,
    Authentication a
  ) {
    return messages.block(member, b.enabled, auth.id(a));
  }

  @GetMapping("/{id}")
  Object detail(@PathVariable String id, Authentication a) {
    return messages.detail(id, auth.id(a));
  }

  @PostMapping("/{id}")
  Object send(@PathVariable String id, @RequestBody Text t, Authentication a) {
    return messages.send(id, t.body, auth.id(a));
  }

  @PostMapping("/{id}/respond")
  Object respond(
    @PathVariable String id,
    @RequestBody Response r,
    Authentication a
  ) {
    return messages.respond(id, r.accept, auth.id(a));
  }

  @PostMapping("/trip/{trip}")
  Object group(@PathVariable String trip, Authentication a) {
    return messages.group(trip, auth.id(a));
  }
}
