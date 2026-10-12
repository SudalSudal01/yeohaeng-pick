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

`schedule/preview`에 `{version}`을 보내면 `EARLIER`, `LATER` 두 조정안을 반환합니다. 각 안은 `canApply`, 변경 전후 `changes`, 합산 변경 분 `totalShiftMinutes`, `conflicts`, 전체 방문 `items`를 포함합니다. 방문일·순서·장소·체류 시간·비용·예약 상태를 유지하고, 고정 방문은 이동하지 않으며 06:00~22:00 범위를 지킵니다. 방문 사이 이동은 기존 거리 기반 추정 + 20분 간격입니다. 한 방향으로만 조정하는 방식이므로 두 안 실패가 모든 조합의 불가능을 뜻하지 않습니다. 영업시간/여행일 운행/좌석/실제 탑승 가능성을 보장하지 않습니다.

`schedule/apply`는 `{version,strategy:"EARLIER"|"LATER"}`만 받고 서버가 최신 저장 데이터로 다시 계산합니다. 임의 변경 시각을 저장하지 않습니다. 접근 권한·CSRF·낙관적 버전 비교 후 같은 트랜잭션에서 방문 일정과 이력을 저장합니다. 예약 데이터는 그대로 유지합니다. 장소 변경 비교/적용과 이전 방문 일정 복원에도 현재 교통 예약의 시간 충돌 검사를 적용합니다. 예약 수정·취소는 별도 기록 저장으로만 수행합니다.

## 데이터 저장

회원·여행·참여자·일정·숙소·외부 예약·초대·지출·준비물·게시물·댓글·대화·차단·신고는 정규 테이블로 관리합니다. 취향 목록과 방문 일정 이력 스냅샷만 JSON 텍스트로 저장합니다. 현재 목록 조회는 규모 제한/페이지네이션을 보강해야 합니다. 계약의 TypeScript 표현은 `frontend/src/shared/types.ts`, 실제 검증은 Java 요청 DTO가 기준입니다.

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
