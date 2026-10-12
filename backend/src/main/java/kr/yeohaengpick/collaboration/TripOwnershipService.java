package kr.yeohaengpick.collaboration;

import java.util.*;
import kr.yeohaengpick.common.DomainException;
import kr.yeohaengpick.trip.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 현재 소유자만 이미 수락한 참여자에게 소유권을 이전합니다. 기존 소유자는 일반 동행으로 남습니다. */
@Service
public class TripOwnershipService {

  private final TripService trips;
  private final TripMapper members;
  private final TripOwnershipMapper ownership;

  public TripOwnershipService(
    TripService trips,
    TripMapper members,
    TripOwnershipMapper ownership
  ) {
    this.trips = trips;
    this.members = members;
    this.ownership = ownership;
  }

  @Transactional
  public Object transfer(String id, String target, int version, String member) {
    // 모든 여행 쓰기와 동일하게 여행 행부터 잠급니다. 대상의 나가기/참여 해제와도 경합을 막습니다.
    var trip = trips.access(id, member);
    if (!member.equals(trip.get("ownerId"))) throw new DomainException(
      403,
      "현재 여행 소유자만 소유권을 넘길 수 있어요."
    );
    if (member.equals(target)) throw DomainException.bad(
      "소유권을 넘길 다른 동행을 선택해 주세요."
    );
    if (members.access(id, target) == 0) throw new DomainException(
      404,
      "현재 참여 중인 동행을 선택해 주세요. 초대 수락 전에는 소유권을 넘길 수 없어요."
    );
    if (
      ownership.transfer(id, member, target, version) != 1
    ) throw new DomainException(
      409,
      "여행이 변경됐어요. 최신 참여자와 소유자를 확인한 뒤 다시 진행해 주세요."
    );
    int revoked = ownership.revokePending(id);
    ownership.record(
      UUID.randomUUID().toString(),
      id,
      member,
      target,
      version + 1,
      revoked
    );
    // 참여 행·일정·예약·경비·대화·공개 링크는 그대로 둡니다. 외부 결제/예약 명의 이전을 뜻하지 않습니다.
    return trips.detail(id, member);
  }
}
