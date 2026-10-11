package kr.yeohaengpick.common;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

/** 내부 SQL·스택·키를 노출하지 않는 일관된 오류 응답. 프론트는 message를 안내문으로 사용합니다. */
@RestControllerAdvice
public class ApiErrors {

  @ExceptionHandler(DomainException.class)
  ResponseEntity<?> domain(DomainException e) {
    return ResponseEntity.status(e.status).body(
      Map.of("message", e.getMessage())
    );
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
  })
  ResponseEntity<?> validation(Exception e) {
    return ResponseEntity.badRequest().body(
      Map.of("message", "입력한 값의 형식과 길이를 확인해 주세요.")
    );
  }

  @ExceptionHandler(
    org.springframework.dao.DataIntegrityViolationException.class
  )
  ResponseEntity<?> conflict(Exception e) {
    return ResponseEntity.status(409).body(
      Map.of(
        "message",
        "이미 처리된 요청이거나 저장할 수 없는 값이에요. 새로고침 후 확인해 주세요."
      )
    );
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    String reference = java.util.UUID.randomUUID().toString();
    // SQL 예외 문자열에는 이메일 등이 포함될 수 있어 요청 식별자와 오류 종류만 기록합니다.
    org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error(
      "처리 실패 {}: {}",
      reference,
      e.getClass().getSimpleName()
    );
    return ResponseEntity.internalServerError().body(
      Map.of(
        "message",
        "잠시 문제가 생겼어요. 다시 시도해 주세요.",
        "reference",
        reference
      )
    );
  }
}
