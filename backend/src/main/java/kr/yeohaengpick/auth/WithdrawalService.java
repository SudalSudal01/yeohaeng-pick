package kr.yeohaengpick.auth;

import java.util.*;
import kr.yeohaengpick.common.DomainException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 탈퇴는 복원 기능이 없습니다. 소유 여행을 자동 삭제하지 않고 명시적으로 정리하도록 막습니다. */
@Service
public class WithdrawalService {

  private final WithdrawalMapper withdrawal;
  private final MemberMapper members;
  private final PasswordEncoder encoder;

  public WithdrawalService(
    WithdrawalMapper withdrawal,
    MemberMapper members,
    PasswordEncoder encoder
  ) {
    this.withdrawal = withdrawal;
    this.members = members;
    this.encoder = encoder;
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
    withdrawal.leaveAll(member);
    withdrawal.invitations(member);
    withdrawal.blocks(member);
    withdrawal.tokens(member);
    withdrawal.imports(member);
    withdrawal.closeDirectRooms(member);
    // 공개 글/댓글은 휴지통 본문까지 지웁니다. 타인의 댓글·공동 여행·대화·신고 증거는 삭제하지 않습니다.
    withdrawal.erasePosts(member);
    withdrawal.eraseComments(member);
    if (
      withdrawal.anonymize(member, "withdrawn:" + UUID.randomUUID()) != 1
    ) throw new DomainException(409, "이미 처리된 계정이에요.");
  }
}
