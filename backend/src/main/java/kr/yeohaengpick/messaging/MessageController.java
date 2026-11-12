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

  /** requestId는 클라이언트가 첫 전송 때 한 번 만든 UUID입니다. 같은 요청의 재시도에만 재사용합니다. */
  public record Text(String body, String requestId) {}

  public record Response(boolean accept) {}

  public record Block(boolean enabled) {}

  @GetMapping
  Object list(Authentication a) {
    return messages.list(auth.id(a));
  }

  @GetMapping("/recipients")
  Object recipients(
    @RequestParam(defaultValue = "") String query,
    Authentication a
  ) {
    return messages.recipients(query, auth.id(a));
  }

  @GetMapping("/recipients/{member}")
  Object recipient(@PathVariable String member, Authentication a) {
    return messages.recipient(member, auth.id(a));
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
  Object detail(
    @PathVariable String id,
    @RequestParam(required = false) String before,
    Authentication a
  ) {
    return messages.detail(id, before, auth.id(a));
  }

  @PostMapping("/{id}")
  Object send(@PathVariable String id, @RequestBody Text t, Authentication a) {
    return messages.send(id, t.body, t.requestId, auth.id(a));
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
