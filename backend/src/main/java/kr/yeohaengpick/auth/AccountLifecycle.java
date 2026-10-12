package kr.yeohaengpick.auth;

import java.util.concurrent.locks.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

/**
 * 단일 JVM에서 탈퇴와 이미 시작한 요청의 경합을 막습니다. 일반 요청은 공유 잠금이라 서로 병렬 실행됩니다.
 * 탈퇴만 배타 잠금을 사용해 진행 중인 초대·소유권·메일 요청이 끝난 다음 관련 자료를 한 번에 정리합니다.
 * 계정별 잠금을 중첩하면 서로 연락하는 두 계정의 탈퇴에서 교착될 수 있어 서버 하나의 경계를 사용합니다.
 * 다중 서버 배포 전에는 이 경계를 DB/분산 잠금으로 교체해야 합니다. 여행 자체의 DB 행 잠금은 별도로 유지합니다.
 */
@Component
public class AccountLifecycle {

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

  public Lock requestLock(boolean withdrawal) {
    return withdrawal ? lock.writeLock() : lock.readLock();
  }

  /** 익명 로그인·메일도 같은 경계를 사용합니다. 트랜잭션이면 커밋·롤백까지 유지합니다. */
  public <T> T reading(Supplier<T> action) {
    Lock lock = requestLock(false);
    lock.lock();
    try {
      return action.get();
    } finally {
      if (
        TransactionSynchronizationManager.isSynchronizationActive() &&
        TransactionSynchronizationManager.isActualTransactionActive()
      ) {
        TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              lock.unlock();
            }
          }
        );
      } else lock.unlock();
    }
  }
}
