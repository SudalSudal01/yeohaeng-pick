package kr.yeohaengpick.common;

/** 서비스가 예상하는 실패를 HTTP 상태와 사용자에게 이해되는 문장으로 전달합니다. */
public class DomainException extends RuntimeException {

  public final int status;

  public DomainException(int status, String message) {
    super(message);
    this.status = status;
  }

  public static DomainException bad(String message) {
    return new DomainException(400, message);
  }
}
