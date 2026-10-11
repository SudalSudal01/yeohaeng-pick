package kr.yeohaengpick.auth;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;
import kr.yeohaengpick.common.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증 링크의 원문은 이메일에만 넣고 DB에는 SHA-256 해시만 보관합니다.
 * 재설정 요청은 가입 여부와 관계없이 같은 응답을 반환해 이메일 존재 여부를 숨깁니다.
 */
@Service
public class EmailAccountService {

  private final MemberMapper members;
  private final JavaMailSender mail;
  private final AuthService auth;
  private final org.springframework.security.crypto.password.PasswordEncoder encoder;

  @Value("${app.mail-enabled:false}")
  private boolean enabled;

  @Value("${app.mail-from:}")
  private String from;

  @Value("${app.public-url}")
  private String publicUrl;

  @Value("${app.admin-email:}")
  private String adminEmail;

  public EmailAccountService(
    MemberMapper m,
    JavaMailSender s,
    AuthService a,
    org.springframework.security.crypto.password.PasswordEncoder e
  ) {
    members = m;
    mail = s;
    auth = a;
    encoder = e;
  }

  public void send(String email, String purpose) {
    if (!enabled || from.isBlank()) throw new DomainException(
      503,
      "메일 서비스가 아직 연결되지 않았어요. 운영자에게 문의해 주세요."
    );
    var member = members.byEmail(email.trim().toLowerCase(Locale.ROOT));
    if (member == null) return;
    if (
      purpose.equals("VERIFY") && Boolean.TRUE.equals(member.get("verified"))
    ) return;
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    String token = HexFormat.of().formatHex(random);
    members.token(
      hash(token),
      (String) member.get("id"),
      purpose,
      LocalDateTime.now().plusMinutes(30)
    );
    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo((String) member.get("email"));
    message.setSubject(
      purpose.equals("VERIFY")
        ? "[여행픽] 이메일을 인증해 주세요"
        : "[여행픽] 비밀번호 재설정 안내"
    );
    message.setText(
      "요청하신 " +
        (purpose.equals("VERIFY") ? "이메일 인증" : "비밀번호 재설정") +
        " 링크입니다. 30분 이내에 이용해 주세요.\n\n" +
        publicUrl +
        "/account/" +
        (purpose.equals("VERIFY") ? "verify" : "reset") +
        "?token=" +
        token +
        "\n\n본인이 요청하지 않았다면 이 메일을 무시해 주세요."
    );
    try {
      mail.send(message);
    } catch (org.springframework.mail.MailException ex) {
      members.deleteToken(hash(token));
      throw new DomainException(
        503,
        "메일을 전송하지 못했어요. 잠시 후 다시 시도해 주세요."
      );
    }
  }

  @Transactional
  public void consume(String token, String purpose, String password) {
    if (
      token == null || !token.matches("[0-9a-f]{64}")
    ) throw DomainException.bad("유효하지 않은 링크예요.");
    String hashed = hash(token);
    var stored = members.tokenByHash(hashed);
    if (
      stored == null || !purpose.equals(stored.get("purpose"))
    ) throw DomainException.bad("만료되었거나 이미 사용한 링크예요.");
    String id = (String) stored.get("memberId");
    if (purpose.equals("RESET")) auth.validatePassword(password);
    // DELETE의 영향받은 행 수를 확인해 동시 요청에서도 한 번만 사용되도록 합니다.
    if (members.consumeToken(hashed) != 1) throw DomainException.bad(
      "이미 사용한 링크예요."
    );
    if (purpose.equals("VERIFY")) {
      members.verify(id);
      if (members.byId(id).get("email").equals(adminEmail)) members.promote(id);
    } else {
      members.password(id, encoder.encode(password));
      members.invalidateTokens(id, "RESET");
    }
  }

  private String hash(String value) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          value.getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
