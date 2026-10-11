package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import kr.yeohaengpick.common.*;
import kr.yeohaengpick.transport.TransportRoutes;
import org.junit.jupiter.api.Test;

/** 실제 공급자 계약의 단위와 '미제공/무료/결과 없음/장애'를 구분합니다. 외부 호출 없이 재현합니다. */
class TransportRoutesTest {

  final JsonCodec json = new JsonCodec();

  @Test
  void transitKeepsUnknownFareAndDisconnectedGeometry() {
    var root = json.mapper().readTree(
      """
      {"status":"OK","routes":[{"properties":{"totalDistance":2500,"totalTime":901,"transfers":1},"steps":[
        {"properties":{"type":"WALKING","time":61,"guidance":"정류장 이동"},"path":{"points":[[126.97,37.55],[126.98,37.56]]}},
        {"properties":{"type":"BUS","time":840,"guidance":"버스 이동","vehicles":[{"name":"100"}]},"path":{"points":[[127.01,37.58],[127.02,37.59]]}}
      ]}]}
      """
    );
    var option = TransportRoutes.parse("TRANSIT", root).getFirst();
    assertThat(option.minutes()).isEqualTo(16);
    assertThat(option.fare()).isNull();
    assertThat(option.transfers()).isEqualTo(1);
    assertThat(option.walkingMinutes()).isEqualTo(2);
    assertThat(option.paths()).hasSize(2);
    assertThat(option.paths().getFirst().getFirst().latitude()).isEqualTo(
      37.55
    );
    assertThat(option.steps().getLast().vehicles()).containsExactly("100");
  }

  @Test
  void carTollZeroIsKnownButTaxiFareIsNotTransitFare() {
    var root = json.mapper().readTree(
      """
      {"routes":[{"result_code":0,"summary":{"distance":5000,"duration":601,"fare":{"toll":0,"taxi":12000}},"sections":[{"roads":[{"vertexes":[126.97,37.55,126.98,37.56]}]}]}]}
      """
    );
    var option = TransportRoutes.parse("CAR", root).getFirst();
    assertThat(option.toll()).isZero();
    assertThat(option.fare()).isNull();
    assertThat(option.minutes()).isEqualTo(11);
    assertThat(option.paths().getFirst()).hasSize(2);
  }

  @Test
  void noResultsIsEmptyButMalformedSuccessIsAnError() {
    assertThat(
      TransportRoutes.parse(
        "TRANSIT",
        json.mapper().readTree("{\"status\":\"NO_RESULTS\"}")
      )
    ).isEmpty();
    assertThat(
      TransportRoutes.parse(
        "WALK",
        json.mapper().readTree("{\"status\":\"ROUTE_RESULT_NOT_FOUND\"}")
      )
    ).isEmpty();
    assertThatThrownBy(() ->
      TransportRoutes.parse(
        "WALK",
        json.mapper().readTree("{\"status\":\"OK\",\"route\":{}}")
      )
    ).isInstanceOf(DomainException.class);
  }

  @Test
  void walkHasNoInventedFareAndOnlyThreeProviderRoutesAreShown() {
    var walk = TransportRoutes.parse(
      "WALK",
      json.mapper().readTree(
        """
        {"status":"OK","route":{"properties":{"totalDistance":900,"totalTime":600},"legs":[{"steps":[{"properties":{"guidance":"직진","time":600},"path":{"points":[[126.97,37.55],[126.98,37.56]]}}]}]}}
        """
      )
    ).getFirst();
    assertThat(walk.fare()).isNull();
    assertThat(walk.minutes()).isEqualTo(10);
    List<Object> routes = new ArrayList<>();
    for (int duration : List.of(900, 300, 500, 700))
      routes.add(
        Map.of(
          "properties",
          Map.of("totalDistance", 1000, "totalTime", duration),
          "steps",
          List.of()
        )
      );
    assertThat(
      TransportRoutes.parse(
        "TRANSIT",
        json.mapper().valueToTree(Map.of("status", "OK", "routes", routes))
      )
        .stream()
        .map(TransportRoutes.Option::minutes)
    ).containsExactly(5, 9, 12);
    assertThat(
      TransportRoutes.parse(
        "TRANSIT",
        json.mapper().valueToTree(Map.of("status", "OK", "routes", routes))
      )
        .getFirst()
        .walkingMinutes()
    ).isNull();
  }
}
