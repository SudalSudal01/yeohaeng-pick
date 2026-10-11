package kr.yeohaengpick.transport;

import java.util.*;
import kr.yeohaengpick.common.DomainException;
import tools.jackson.databind.JsonNode;

/** 공급자별 서로 다른 단위·기하 구조를 화면 계약으로 변환합니다. 응답에 없는 요금을 0원으로 만들지 않습니다. */
public final class TransportRoutes {

  private TransportRoutes() {}

  public record Point(
    String id,
    String label,
    double latitude,
    double longitude
  ) {}

  public record Coordinate(double latitude, double longitude) {}

  public record Step(
    String type,
    String guidance,
    int minutes,
    List<String> vehicles
  ) {}

  public record Option(
    String id,
    String mode,
    int distanceMeters,
    int minutes,
    Integer transfers,
    Integer fare,
    Integer toll,
    Integer walkingMinutes,
    List<Step> steps,
    List<List<Coordinate>> paths
  ) {}

  public record Result(
    Point from,
    Point to,
    String mode,
    String queriedAt,
    String source,
    List<Option> options,
    String externalUrl
  ) {}

  public static List<Option> parse(String mode, JsonNode root) {
    List<Option> result = new ArrayList<>();
    if (mode.equals("CAR")) {
      if (!root.path("routes").isArray()) throw unavailable();
      for (var route : root.path("routes")) {
        if (route.path("result_code").asInt(-1) != 0) continue;
        var summary = route.path("summary");
        List<List<Coordinate>> paths = new ArrayList<>();
        for (var section : route.path("sections"))
          for (var road : section.path("roads")) {
            List<Coordinate> points = new ArrayList<>();
            var vertices = road.path("vertexes");
            for (int i = 0; i + 1 < vertices.size(); i += 2) add(
              points,
              vertices.get(i + 1).asDouble(Double.NaN),
              vertices.get(i).asDouble(Double.NaN)
            );
            append(paths, points);
          }
        result.add(
          new Option(
            "car-" + result.size(),
            mode,
            distance(summary, "distance"),
            minutes(summary, "duration"),
            null,
            null,
            amount(summary.path("fare"), "toll"),
            null,
            List.of(),
            paths
          )
        );
      }
    } else {
      String status = root.path("status").asText();
      if (
        List.of(
          "NO_RESULTS",
          "STARTNODES_NULL",
          "ENDNODES_NULL",
          "START_LINK_NOT_FOUND",
          "END_LINK_NOT_FOUND",
          "TOO_FAR_AWAY",
          "ROUTE_RESULT_NOT_FOUND",
          "TOO_MANY_SEARCH_LINK",
          "EQUAL_POINTS",
          "SAME_POINT"
        ).contains(status)
      ) return List.of();
      if (!status.equals("OK")) throw unavailable();
      if (mode.equals("WALK")) {
        var route = root.path("route");
        var properties = route.path("properties");
        List<List<Coordinate>> paths = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        for (var leg : route.path("legs"))
          for (var step : leg.path("steps"))
            readStep(step, steps, paths, "WALKING");
        int duration = minutes(properties, "totalTime");
        result.add(
          new Option(
            "walk-0",
            mode,
            distance(properties, "totalDistance"),
            duration,
            null,
            null,
            null,
            duration,
            steps,
            paths
          )
        );
      } else {
        if (!root.path("routes").isArray()) throw unavailable();
        for (var route : root.path("routes")) {
          var properties = route.path("properties");
          List<Step> steps = new ArrayList<>();
          List<List<Coordinate>> paths = new ArrayList<>();
          for (var step : route.path("steps"))
            readStep(step, steps, paths, "WALKING");
          // 도보 단계가 생략된 응답을 '도보 0분'으로 확정하지 않습니다.
          Integer walking = steps
            .stream()
            .noneMatch(s -> s.type().equals("WALKING"))
            ? null
            : steps
                .stream()
                .filter(s -> s.type().equals("WALKING"))
                .mapToInt(Step::minutes)
                .sum();
          result.add(
            new Option(
              "transit-" + result.size(),
              mode,
              distance(properties, "totalDistance"),
              minutes(properties, "totalTime"),
              amount(properties, "transfers"),
              amount(properties.path("fare"), "value"),
              null,
              walking,
              steps,
              paths
            )
          );
        }
      }
    }
    // 공급자가 제공한 경로 중 빠른 순으로 최대 3개만 보여 줍니다. 부족한 개수는 생성하지 않습니다.
    return result
      .stream()
      .sorted(
        Comparator.comparingInt(Option::minutes).thenComparingInt(
          Option::distanceMeters
        )
      )
      .limit(3)
      .toList();
  }

  private static void readStep(
    JsonNode step,
    List<Step> steps,
    List<List<Coordinate>> paths,
    String fallbackType
  ) {
    var p = step.path("properties");
    List<String> vehicles = new ArrayList<>();
    for (var vehicle : p.path("vehicles"))
      if (!vehicle.path("name").asText().isBlank()) vehicles.add(
        vehicle.path("name").asText()
      );
    steps.add(
      new Step(
        p.path("type").asText(fallbackType),
        p.path("guidance").asText(),
        minutes(p, "time"),
        vehicles
      )
    );
    List<Coordinate> points = new ArrayList<>();
    for (var point : step.path("path").path("points"))
      if (point.size() >= 2) add(
        points,
        point.get(1).asDouble(Double.NaN),
        point.get(0).asDouble(Double.NaN)
      );
    append(paths, points);
  }

  private static void add(
    List<Coordinate> points,
    double latitude,
    double longitude
  ) {
    if (
      Double.isFinite(latitude) &&
      Double.isFinite(longitude) &&
      latitude >= -90 &&
      latitude <= 90 &&
      longitude >= -180 &&
      longitude <= 180
    ) points.add(new Coordinate(latitude, longitude));
  }

  private static void append(
    List<List<Coordinate>> paths,
    List<Coordinate> points
  ) {
    // 도로/이동 단계의 틈을 직선으로 이어 실제 경로인 것처럼 표시하지 않습니다.
    if (points.size() >= 2) paths.add(List.copyOf(points));
  }

  private static int distance(JsonNode node, String name) {
    Integer value = amount(node, name);
    if (value == null || value == 0) throw unavailable();
    return value;
  }

  private static int minutes(JsonNode node, String name) {
    Integer seconds = amount(node, name);
    if (seconds == null) throw unavailable();
    return (int) Math.ceil(seconds / 60.0);
  }

  private static Integer amount(JsonNode node, String name) {
    var value = node.path(name);
    return value.isNumber() && value.canConvertToInt() && value.asInt() >= 0
      ? value.asInt()
      : null;
  }

  private static DomainException unavailable() {
    return new DomainException(
      503,
      "경로 응답을 확인하지 못했어요. 잠시 후 다시 조회해 주세요."
    );
  }
}
