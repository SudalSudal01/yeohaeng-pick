package kr.yeohaengpick.trip;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import kr.yeohaengpick.common.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 화면의 연속 클릭 방지와 별개로, 저장에 성공하고 응답만 잃은 명령을 DB에서 한 번으로 처리합니다.
 * 여행·준비물·경비 원문과 응답 JSON은 보관하지 않고 의미 있는 입력의 해시와 생성 ID만 남깁니다.
 * 키 없는 예전 요청은 기존 동작을 유지합니다. 영수증과 실제 저장은 반드시 호출자의 같은 트랜잭션입니다.
 */
@Component
public class TripMutationReceipts {

  public enum Operation {
    TRIP_CREATE,
    TASK_ADD,
    EXPENSE_ADD,
  }

  public record Receipt(String tripId, String targetId) {}

  public record Command(
    String member,
    Operation operation,
    String requestId,
    String hash,
    Receipt previous
  ) {}

  private final TripMutationMapper mutations;
  private final JsonCodec json;

  public TripMutationReceipts(TripMutationMapper mutations, JsonCodec json) {
    this.mutations = mutations;
    this.json = json;
  }

  public Command begin(
    String member,
    Operation operation,
    String requestId,
    Object payload
  ) {
    if (requestId == null) return null;
    String key;
    try {
      if (requestId.length() != 36) throw new IllegalArgumentException();
      key = UUID.fromString(requestId).toString();
      if (
        !key.equalsIgnoreCase(requestId)
      ) throw new IllegalArgumentException();
    } catch (IllegalArgumentException failure) {
      throw DomainException.bad(
        "저장 요청 식별자가 올바르지 않아요. 입력 화면을 다시 열어 주세요."
      );
    }
    if (
      !TransactionSynchronizationManager.isActualTransactionActive()
    ) throw new IllegalStateException(
      "생성 영수증은 실제 저장과 같은 트랜잭션에서 사용해야 합니다."
    );
    // 같은 회원의 같은 작업·키가 동시에 도착해도 첫 성공이 끝난 뒤에만 영수증을 다시 읽습니다.
    // 이 잠금 이후 기존 여행을 잠그는 순서를 지켜 초대·공유 가져오기와 반대 순서를 만들지 않습니다.
    if (mutations.lockMember(member) == null) throw new DomainException(
      401,
      "다시 로그인해 주세요."
    );
    String hash = fingerprint(payload);
    var row = mutations.receipt(member, operation.name(), key);
    if (
      row != null && !hash.equals(row.get("payloadHash"))
    ) throw new DomainException(
      409,
      "이미 다른 내용에 사용한 저장 요청이에요. 입력 내용을 확인하고 새 요청으로 보내 주세요."
    );
    return new Command(
      member,
      operation,
      key,
      hash,
      row == null
        ? null
        : new Receipt((String) row.get("tripId"), (String) row.get("targetId"))
    );
  }

  public void complete(Command command, String trip, String target) {
    if (command == null) return;
    if (command.previous() != null) throw new IllegalStateException(
      "이미 성공한 생성 명령을 다시 실행할 수 없습니다."
    );
    mutations.record(
      command.member(),
      command.operation().name(),
      command.requestId(),
      command.hash(),
      trip,
      target
    );
  }

  public int targetExists(Command command, String trip) {
    return switch (command.operation()) {
      case TRIP_CREATE -> command.previous().targetId().equals(trip) ? 1 : 0;
      case TASK_ADD -> mutations.taskExists(
        trip,
        command.previous().targetId()
      );
      case EXPENSE_ADD -> mutations.expenseExists(
        trip,
        command.previous().targetId()
      );
    };
  }

  /** Map.of의 순회 순서는 JVM 재시작마다 달라질 수 있어 모든 객체 키를 정렬한 뒤 해시합니다. */
  private String fingerprint(Object payload) {
    Object value = json.read(json.write(payload), Object.class);
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          json.write(canonical(value)).getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private Object canonical(Object value) {
    if (value instanceof Map<?, ?> object) {
      var sorted = new TreeMap<String, Object>();
      object.forEach((key, item) ->
        sorted.put(key.toString(), canonical(item))
      );
      return sorted;
    }
    if (value instanceof List<?> array) return array
      .stream()
      .map(this::canonical)
      .toList();
    return value;
  }
}
