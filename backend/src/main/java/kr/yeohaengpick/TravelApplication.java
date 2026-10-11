package kr.yeohaengpick;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 여행픽의 실행 진입점. 하위 기능 패키지를 자동으로 탐색하는 Spring Boot 애플리케이션입니다. */
@SpringBootApplication
public class TravelApplication {

  public static void main(String[] args) {
    SpringApplication.run(TravelApplication.class, args);
  }
}
