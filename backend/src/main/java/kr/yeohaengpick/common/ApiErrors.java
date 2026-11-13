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
    org.springframework.beans.TypeMismatchException.class,
    jakarta.validation.ConstraintViolationException.class,
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
    // 잘못된 경로·필수 파라미터 누락·지원하지 않는 메서드/형식은 서버 장애가 아닙니다.
    // 프레임워크의 상태는 보존하되 원문에는 요청 경로나 내부 타입이 들어갈 수 있어 노출하지 않습니다.
    if (e instanceof org.springframework.web.ErrorResponse expected) {
      int status = expected.getStatusCode().value();
      if (status >= 400 && status < 500) {
        String message = switch (status) {
          case 404 -> "요청한 정보를 찾을 수 없어요.";
          case 405 -> "이 주소에서 지원하지 않는 요청 방식이에요.";
          case 406, 415 -> "지원하지 않는 데이터 형식이에요.";
          case 413 -> "전송할 수 있는 크기를 초과했어요.";
          default -> "입력한 값의 형식과 필수 항목을 확인해 주세요.";
        };
        var response = ResponseEntity.status(status);
        // 405의 Allow는 클라이언트가 올바른 방식으로 복구하는 데 필요한 표준 헤더입니다.
        if (!expected.getHeaders().getAllow().isEmpty()) response.allow(
          expected
            .getHeaders()
            .getAllow()
            .toArray(org.springframework.http.HttpMethod[]::new)
        );
        return response.body(Map.of("message", message));
      }
    }
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
