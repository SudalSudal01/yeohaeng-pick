package kr.yeohaengpick.auth;

import java.util.*;
import kr.yeohaengpick.common.DomainException;
import kr.yeohaengpick.notification.NotificationService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 탈퇴는 복원 기능이 없습니다. 소유 여행을 자동 삭제하지 않고 명시적으로 정리하도록 막습니다. */
@Service
public class WithdrawalService {

  private final WithdrawalMapper withdrawal;
  private final MemberMapper members;
  private final PasswordEncoder encoder;
  private final NotificationService notifications;

  public WithdrawalService(
    WithdrawalMapper withdrawal,
    MemberMapper members,
    PasswordEncoder encoder,
    NotificationService notifications
  ) {
    this.withdrawal = withdrawal;
    this.members = members;
    this.encoder = encoder;
    this.notifications = notifications;
  }

  public Object preview(String member) {
    var owned = withdrawal.owned(member);
    return Map.of(
      "ownedTrips",
      owned,
      "joinedTripCount",
      Math.max(0, withdrawal.participation(member) - owned.size())
    );
  }

  @Transactional
  public void withdraw(String member, String password) {
    if (
      password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
    ) throw DomainException.bad(
      "비밀번호는 UTF-8 기준 72바이트 이하로 입력해 주세요."
    );
    var account = members.byId(member);
    if (!MemberMapper.active(account)) throw new DomainException(
      401,
      "로그인이 필요해요."
    );
    if (
      !encoder.matches(password, (String) account.get("passwordHash"))
    ) throw new DomainException(403, "현재 비밀번호를 확인해 주세요.");
    // 화면의 사전 조회 결과를 믿지 않습니다. 이전 요청이 끝난 뒤 최신 소유권을 잠금 안에서 다시 검사합니다.
    var trips = withdrawal.lockTrips(member);
    if (
      trips.stream().anyMatch(t -> member.equals(t.get("ownerId")))
    ) throw new DomainException(
      409,
      "소유한 여행이 남아 있어요. 소유권을 넘기거나 여행을 삭제한 뒤 다시 진행해 주세요."
    );
    trips.forEach(t -> withdrawal.bump((String) t.get("id")));
    // 참여 연결을 끊기 전에 표를 지워 재가입이나 재초대가 과거 선택을 복구하지 않게 합니다.
    // 탈퇴 회원이 만든 질문과 다른 참여자의 집계는 공동 여행 기록으로 유지합니다.
    withdrawal.pollVotes(member);
    // 수신 알림은 개인 받은 편지함이므로 익명화 전에 전부 지웁니다. 이 회원이 행동자로 남은
    // 다른 사람의 알림은 공동 기록이며, 이후 '탈퇴한 회원' 닉네임으로 안전하게 표시됩니다.
    notifications.clearRecipient(member);
    withdrawal.leaveAll(member);
    withdrawal.invitations(member);
    withdrawal.blocks(member);
    withdrawal.tokens(member);
    withdrawal.imports(member);
    withdrawal.favorites(member);
    withdrawal.closeDirectRooms(member);
    // 공개 글/댓글은 휴지통 본문까지 지웁니다. 타인의 댓글·공동 여행·대화·신고 증거는 삭제하지 않습니다.
    withdrawal.erasePosts(member);
    withdrawal.eraseComments(member);
    if (
      withdrawal.anonymize(member, "withdrawn:" + UUID.randomUUID()) != 1
    ) throw new DomainException(409, "이미 처리된 계정이에요.");
  }
}
