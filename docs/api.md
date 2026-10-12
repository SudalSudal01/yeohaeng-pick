# API와 데이터 계약

루트는 `/api`입니다. 브라우저는 쿠키 세션을 이용하며 변경 요청 전 `GET /auth/csrf`로 토큰을 얻어 `X-XSRF-TOKEN`에 넣습니다. 공통 오류는 `{message, reference?}`입니다. 401 로그인 필요, 403 권한/CSRF, 409 버전/상태 충돌, 429 요청 제한, 503 외부 준비/장애로 처리합니다.

| 영역 | 경로 | 설명 |
| --- | --- | --- |
| 공개 설정 | GET /config | 지도 공개 키와 실제 연결 상태. 서버 비밀 키 제외 |
| 인증 | POST /auth/register, /login, /logout | 가입·로그인·로그아웃 |
| 계정 | GET /auth/me, PUT /auth/profile | 기본 취향과 수신 범위 |
| 메일 | POST /auth/verify/send, /verify, /reset/send, /reset | 발송·일회용 인증·비밀번호 재설정 |
| 장소 | GET /catalog/places?region=&query=&category= | 서버에서 여행 업종·실제 주소·분류 필터 적용 |
| 출발지 검색 | POST /trips/{id}/transport/origins | `{query}` → 회원별 30분 검색 결과 토큰 |
| 실제 경로 | POST /trips/{id}/transport/routes | 저장 일정·숙소·출발지 토큰 → 경로/요금/좌표 |
| 추천 | POST /recommendations | 여행 조건 → 세 코스, 엔진/경고/숙소 추정 포함 |
| 여행 | GET/POST /trips, GET/DELETE /trips/{id} | 생성·조회·소유자 삭제 |
| 대안 | GET /trips/{id}/alternatives/{item}?rain= | 중복 제외, 선택적 실내 후보 |
| 비교/적용 | POST /trips/{id}/preview, /apply | `{itemId, placeId, version}` |
| 예약 | PUT /trips/{id}/booking, /lodging-booking | `{itemId,status,reason,fixed,version}` |
| 외부 예매 | POST /trips/{id}/external-bookings | 열차·버스·항공 등 결과 직접 기록 |
| 예약 시간 조정 | POST /trips/{id}/schedule/preview, /schedule/apply | `{version, strategy?}`. 앞당기기·늦추기 비교 후 명시적 적용 |
| 복원 | POST /trips/{id}/undo | `{historyId,version}`. 방문 일정만 복원 |
| 공유 | POST /trips/{id}/share | 소유자만 생성/종료, `{enabled}` |
| 공개 일정 | GET /share/{token} | 안전한 필드만 노출 |
| 준비물/경비 | POST /trips/{id}/tasks, /expenses | 참여자 공동 기록 |
| 초대 | GET/POST /invitations, POST /invitations/{id}/respond | 수락 전 여행 접근 불가 |
| 커뮤니티 | GET/POST /community/posts, GET /community/posts/{id} | 목록·작성·상세 |
| 댓글/모집 | POST /community/posts/{id}/comments, PUT /community/posts/{id}/status | 댓글과 작성자의 모집 상태 |
| 신고/관리자 | POST /reports, GET /admin/reports, POST /admin/reports/{id}/resolve | 신고 및 처리 |
| 대화 | GET/POST /messages, GET/POST /messages/{id} | 요청·조회·수락 후 추가 발송 |
| 수락/단체 | POST /messages/{id}/respond, /messages/trip/{id} | 수락·거절·여행 단체 대화 |
| 차단 | GET /messages/blocks, PUT /messages/blocks/{member} | 사용자 차단/해제 |

## 예약 상태

`NONE` 예약 전, `NEEDS_CONFIRMATION` 확인 필요, `USER_CONFIRMED` 직접 확인, `FAILED` 실패, `CANCELLED` 취소. `PARTNER_CONFIRMED`는 미래의 제휴 연동용 표시 계약이며 현재 사용자가 설정할 수 없습니다. 장소의 직접 확인 완료는 고정을 활성화합니다. 교통 기록은 직접 확인 완료이면서 `scheduleLinked: true`이고 출발·도착 시각이 입력된 경우에만 시간 제약에 포함합니다.

## 교통 예약과 방문 시간 연결

외부 예매 저장에는 기존 필드 외에 `arrival`(선택, 연결된 직접 확인 예약은 필수), `departurePlace/arrivalPlace`(각 최대 100자), `beforeMinutes/afterMinutes`(각 0~360), `scheduleLinked`를 받습니다. 모든 일시는 한국 현지 시각 `yyyy-MM-ddTHH:mm`이며 여행 기간 안에서 도착이 출발 이후여야 합니다. 자정 이후 도착은 날짜까지 입력합니다. 여유 시간은 역까지 이동·탑승 및 도착 후 이동을 사용자가 확인한 값이며, 기본 30분은 초기 입력값입니다. 렌터카 대여 기간은 실제 이동 전체를 의미하지 않으므로 새 렌터카 기록의 화면 기본 연결은 꺼져 있습니다.

V4 마이그레이션은 기존 예약에 `arrival=null`, `scheduleLinked=false`를 적용합니다. 이전 요청의 생략 필드도 수용하며 기존 기록을 임의로 고정하지 않습니다. 실패·취소·확인 중 기록은 연결 표시가 있어도 방문 시간을 막지 않습니다. 예약의 실제 상황은 충돌하더라도 저장할 수 있고, 상세 응답의 `schedule`에서 충돌을 확인합니다.

`schedule.windows`에는 예약 시각과 `leaveBy`(출발 전 여유 시작), `resumeAt`(도착 후 여유 종료)가 포함됩니다. 방문의 [시작, 체류 종료)와 [leaveBy, resumeAt)가 겹치면 `VISIT_OVERLAP`, 두 예약의 이동·여유 시간이 겹치면 `BOOKING_OVERLAP`입니다. `issues`는 관련 예약/방문 ID와 설명을 제공합니다. `unlinkedCount`는 시간 제약에 포함되지 않은 직접 확인 예약 수입니다. 공개 공유 API는 예약·역 이름·시간 제약 정보를 노출하지 않습니다.

`schedule/preview`에 `{version}`을 보내면 `EARLIER`, `LATER` 두 조정안을 반환합니다. 각 안은 `canApply`, 변경 전후 `changes`, 합산 변경 분 `totalShiftMinutes`, `conflicts`, 전체 방문 `items`를 포함합니다. 방문일·순서·장소·체류 시간·비용·예약 상태를 유지하고, 고정 방문은 이동하지 않으며 06:00~22:00 범위를 지킵니다. 방문 사이 이동은 적용한 구간의 조회 시간 + 사용자 여유를 우선하고, 나머지는 거리 기반 추정 + 20분 간격입니다. 한 방향으로만 조정하는 방식이므로 두 안 실패가 모든 조합의 불가능을 뜻하지 않습니다. 영업시간/여행일 운행/좌석/실제 탑승 가능성을 보장하지 않습니다.

`schedule/apply`는 `{version,strategy:"EARLIER"|"LATER"}`만 받고 서버가 최신 저장 데이터로 다시 계산합니다. 임의 변경 시각을 저장하지 않습니다. 접근 권한·CSRF·낙관적 버전 비교 후 같은 트랜잭션에서 방문 일정과 이력을 저장합니다. 예약 데이터는 그대로 유지합니다. 장소 변경 비교/적용과 이전 방문 일정 복원에도 현재 교통 예약의 시간 충돌 검사를 적용합니다. 예약 수정·취소는 별도 기록 저장으로만 수행합니다.

## 데이터 저장

회원·여행·참여자·일정·숙소·외부 예약·초대·지출·준비물·게시물·댓글·대화·차단·신고는 정규 테이블로 관리합니다. 취향 목록·방문 일정 이력 스냅샷·서버가 검증한 구간별 경로 시간은 JSON 텍스트로 저장합니다. 현재 목록 조회는 규모 제한/페이지네이션을 보강해야 합니다. 계약의 TypeScript 표현은 `frontend/src/shared/types.ts`, 실제 검증은 Java 요청 DTO가 기준입니다.

## 실제 경로 조회 계약

```json
{
  "from": { "kind": "ITEM", "id": "이 여행의 일정 ID" },
  "to": { "kind": "LODGING", "id": "이 여행의 숙소 placeId" },
  "mode": "TRANSIT"
}
```

`kind`는 `ITEM`, `LODGING`, `ORIGIN`입니다. `ORIGIN`에는 출발지 검색 API가 현재 회원에게 발급한 토큰만 허용하며, 임의 좌표나 다른 여행의 항목을 받지 않습니다. 먼저 여행 접근 권한을 확인합니다. `mode`는 `TRANSIT`, `WALK`, `CAR`이며 일정·예약·version을 변경하지 않습니다.

응답의 `from/to`는 라벨과 서버가 확인한 좌표, `queriedAt`은 실제 조회 시각(캐시 사용 시 원래 시각), `source`는 `KAKAO_MAP` 또는 `KAKAO_MOBILITY`입니다. `options`에는 공급자 결과 중 시간순 최대 3개가 들어갑니다. `minutes`는 초 단위를 올림한 분, `distanceMeters`는 미터, `fare`는 대중교통 1인 조회요금, `toll`은 기본 승용차 통행료입니다. 미제공 요금은 `null`이며 차량의 택시 추정 요금을 대중교통 요금으로 사용하지 않습니다. 도보에는 요금이 없습니다. `paths`는 단계/도로별 좌표 배열로 분리하며 틈을 직선으로 연결하지 않습니다.

결과 없음은 `options: []`, 외부 오류/잘못된 성공 응답은 503, 앱/공급자 한도는 429입니다. 자동 재시도를 하지 않습니다. 출발지 토큰과 5분 경로 캐시는 메모리에만 보관하며 각각 최대 1,000개/200개입니다. 일일 보호 한도는 서버 100회·회원 30회(출발지 검색 포함)이고 재시작 시 초기화되므로 분산/운영 과금 방지책을 대체하지 않습니다.

여행일의 운행·좌석·결제 성공을 뜻하지 않습니다. 자동차 유류비·주차비, 인원별 할인, 지역 사이 항공·선박·열차 예매는 별도 확인합니다. 공식 예매처 이동 후 결과는 기존 외부 예약 API로 직접 기록합니다.

## 조회한 경로의 일정 반영

`transport/routes` 응답은 `quoteToken`을 추가로 반환합니다. 서버가 회원·여행·조회 당시 version·장소 좌표·공급자 결과를 묶은 영수증이며, 최대 300개를 메모리에 15분 보관합니다. 서버 재시작·만료·다른 회원의 영수증·조회 후 여행 변경은 409이며 재조회해야 합니다. 원래 경로 캐시의 `queriedAt`은 유지합니다. 지도 선과 개인 출발지 검색 원문은 영수증/DB에 복제하지 않습니다.

`POST /trips/{id}/transport/timing/preview` 입력:

```json
{
  "quoteToken": "경로 조회 응답의 토큰",
  "optionId": "해당 응답의 경로 ID",
  "version": 0,
  "bufferMinutes": 20,
  "departureTime": "09:00"
}
```

`bufferMinutes`는 0~120 정수이며 공급자 조회에 이미 포함된 도보/환승 외 추가 여유입니다. `departureTime`은 숙소 출발에서 `HH:mm`, 날짜 간 이동에서 `yyyy-MM-dd'T'HH:mm` 형식의 한국 현지 일시로 필수입니다. 같은 날 정방향 연속 ITEM→ITEM, 전날 마지막 ITEM→다음 날 첫 ITEM, 같은 지역의 선택 숙소 LODGING→첫 ITEM을 지원합니다. 날짜가 비거나 중간 방문을 건너뛰는 경로는 적용하지 않습니다. 검색 장소·역방향·비연속 구간은 지도 조회만 지원합니다. 서버는 현재 장소 ID·좌표·순서를 재검증하며 클라이언트 분/좌표/시각 배열을 저장하지 않습니다.

응답은 `DEPARTURE`(출발 방문/입력한 숙소·날짜 간 이동 출발 유지), `ARRIVAL`(도착 방문 유지) 두 안입니다. `departure/arrival`은 여유 포함 도착까지의 시각이며 날짜 간 이동에서는 `2일차 11:44`처럼 일차를 포함합니다. `changes`는 방문 전후 시각과 dayIndex, `conflicts`는 고정 방문·예약·하루 범위 문제입니다. 변경되지 않은 구간의 계획상 대기 시간을 가능한 한 보존하고 방문일·순서·체류·활동비 및 각 방문일 06:00~22:00을 지킵니다. 이동 자체는 자정을 넘을 수 있으나 출발 방문을 마치기 전에 이동할 수 없습니다. 방문·교통편뿐 아니라 적용한 이동 구간이 교통 예약의 이동·여유 시간을 가로지르는 경우도 거절합니다. 휴무/영업시간·여행 날짜 운행 검증을 뜻하지 않습니다.

날짜 간 `DEPARTURE`는 입력한 출발 일시를 지키며 다음 날 늦어지는 방문만 미룹니다. 일찍 도착해도 다음 날 방문을 전날로 당기지 않습니다. `ARRIVAL`은 다음 날 첫 방문에서 이동·여유를 빼 출발 일시를 구하고 필요할 때만 전날 방문을 앞당깁니다. 숙소·역 경유와 수면·휴식 시간은 자동 산정하지 않습니다. 저장 기록의 `originDeparture`는 숙소에서 `HH:mm`, 날짜 간 이동에서 한국 현지 ISO 일시입니다.

`POST .../timing/apply`는 위 입력에 `strategy`를 추가합니다. 서버가 재계산하고 조건부 version 증가, 방문·이력·구간 기록을 같은 트랜잭션에 저장합니다. 비교/적용은 외부 API를 추가 호출하지 않습니다. 방문 변화가 없어도 구간 조회값을 저장할 수 있습니다. 조회 요금은 활동비/지출로 합산하지 않습니다.

상세의 `routeTimings`는 현재 장소·좌표·연속 순서에 일치하는 기록만 포함합니다. V5 테이블은 여행+도착 항목별 서버 생성 JSON을 보관하며 일정 항목 재삽입으로 소실되지 않습니다. 장소 교체로 무효해진 입출 구간은 삭제됩니다. 장소 비교·교통 예약 조정·방문 복원에도 저장한 조회값을 반영합니다. 새 예약과 이동 구간 충돌은 `schedule.issues.kind=ROUTE_OVERLAP`으로 안내합니다. 공개 공유에는 구간 기록을 포함하지 않습니다.

`POST .../timing/remove`는 `{itemId,version}`으로 반영을 해제합니다. 현재 방문 시각은 그대로이고, 이후 비교는 그 구간을 거리 추정으로 계산합니다. 이력 복원은 방문만 되돌리므로 경로 기록을 자동 복원/해제하지 않습니다. 현재 조회 시간과 충돌하는 복원은 409이며 필요한 경우 먼저 명시적으로 해제해야 합니다.

## 숙소 경유와 최소 체류 연결

기존 `transport/routes`로 전날 마지막 ITEM→선택 LODGING, 같은 LODGING→다음 날 첫 ITEM을 각각 조회합니다. 두 영수증은 같은 회원·여행·현재 version·이동 수단이어야 합니다. 검색 장소 토큰, 다른 숙소 조합, 날짜 건너뛰기와 중간 방문 생략은 적용할 수 없습니다. 저장한 숙소와 양쪽 방문의 ID·좌표도 서버에서 대조합니다.

`POST /trips/{id}/transport/overnight/preview` 입력:

```json
{
  "inboundToken": "숙소까지 조회한 영수증",
  "inboundOption": "해당 경로 ID",
  "outboundToken": "숙소에서 조회한 영수증",
  "outboundOption": "해당 경로 ID",
  "version": 0,
  "bufferMinutes": 20,
  "minimumStayMinutes": 480,
  "notBefore": "08:00"
}
```

여유는 각 구간에 한 번씩 더하는 0~120분 정수입니다. 최소 숙소 체류는 60~960분의 30분 배수, 다음 날 출발 가능 시각은 한국 현지 `06:00~21:59`입니다. 체류는 체크인·준비를 포함하는 숙소 체류이며 수면 시간이나 객실 사용권을 의미하지 않습니다.

`DEPARTURE`는 전날 방문 종료 후 숙소로 이동해 최소 체류와 출발 가능 시각을 확보하고, 필요한 다음 날 방문만 미룹니다. `ARRIVAL`은 다음 날 첫 방문을 지키며 필요하면 전날 방문을 앞당깁니다. 여유가 충분하면 전날 방문 종료 후 바로 숙소로 이동해 더 오래 머물 수 있습니다. 양일 방문 06:00~22:00, 고정 방문, 교통 예약과 기존 적용 구간을 검사합니다. 숙소 이동뿐 아니라 체류 중의 교통 예약도 충돌입니다.

`POST .../overnight/apply`는 위 입력에 `strategy`를 추가하고 같은 서버 계산을 거쳐 조건부 version 증가·방문·이력·경로 기록을 한 트랜잭션으로 저장합니다. 같은 도착 방문의 기존 단일 구간을 대체합니다. 미리보기/적용은 외부 API를 다시 호출하지 않습니다. 오류·만료·권한·버전 규칙과 해제 API는 단일 구간과 같습니다.

저장 `RouteTiming.overnight`에는 숙소 ID/이름/좌표, 최소 체류, 출발 가능 시각, 실제 계획상 숙소 도착/출발 일시와 inbound/outbound 각각의 분·거리·조회 시각·출처가 포함됩니다. 상위 `minutes`는 이동 두 구간만 합한 값이며 체류·대기를 포함하지 않습니다. V5 서버 JSON에 선택 필드를 추가하므로 기존 JSON은 `overnight=null`로 읽고 스키마 변경은 없습니다. 다른 일정 조정·새 예약·복원에서도 이 숙소 연결을 보호하며 공개 공유에서는 제외합니다.

## 직접 확인한 숙박 조건

`PUT /trips/{id}/lodgings/{placeId}/stay`는 현재 여행에 선택된 숙소만 수정합니다. 입력 예시:

```json
{"version":0,"checkInDate":"2026-11-01","checkOutDate":"2026-11-02","guestCount":2,"roomCount":2,"totalAmount":180000,"checkInTime":"15:00","checkOutTime":"11:00"}
```

날짜는 여행 기간 안에서 1박 이상, 인원은 1~여행 인원, 객실은 1~20개입니다. 인원·객실·금액의 소수는 절삭하지 않고 400으로 거절합니다. 총 숙박비는 전체 기간·전체 객실의 세금 포함 참고 합계이며 0~1억 원, 미확인은 null입니다. 체크인/아웃 시각은 선택적 `HH:mm`이며 미입력 시 날짜만 검사합니다. 객실 정원·재고나 예약 성공을 확인하는 API가 아닙니다. 상세 응답의 숙박 날짜는 시간대 없는 `yyyy-MM-dd`입니다.

모든 조건을 null로 보내거나 `{version}`만 보내면 숙박 조건을 초기화합니다. 날짜 없이 인원·객실·금액만 보내는 부분 입력은 거절합니다. 저장/초기화 모두 회원 접근·CSRF·조건부 version 증가·이력을 거치며 방문 시각·예약 상태·경비 결제 기록은 변경하지 않습니다. 실제 숙박 비용을 결제 완료 경비로 자동 중복 등록하지 않습니다. 공개 공유는 숙소 조건과 금액을 포함하지 않습니다.

숙소→첫 방문 및 숙소 경유 비교/적용은 저장한 체크인/아웃 조건을 함께 검사합니다. 반영한 구간을 깨는 조건 수정은 409이며 기존 연결 해제 또는 조건 조정이 필요합니다. 조건 초기화는 제한을 완화하므로 기존 경로를 유지합니다. 방문 이력 복원은 숙소 조건을 복원하지 않습니다. V6에서 숙소 테이블에 nullable 조건 컬럼을 추가하고 기존 숙소는 미정 상태로 유지합니다.
