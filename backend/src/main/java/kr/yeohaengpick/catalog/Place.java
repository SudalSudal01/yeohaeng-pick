package kr.yeohaengpick.catalog;

/** 장소의 출처를 보존합니다. cost는 예약 가격이 아닌 일정 예산용 추정치이며 프론트에서도 그렇게 표시합니다. */
public record Place(
  String id,
  String name,
  String region,
  String category,
  double latitude,
  double longitude,
  int duration,
  int cost,
  boolean indoor,
  String source,
  String url
) {}
