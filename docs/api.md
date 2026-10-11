# API와 데이터 계약

루트는 `/api`입니다. 브라우저는 쿠키 세션을 이용하며 변경 요청 전 `GET /auth/csrf`로 토큰을 얻어 `X-XSRF-TOKEN`에 넣습니다. 공통 오류는 `{message, reference?}`입니다. 401 로그인 필요, 403 권한/CSRF, 409 버전/상태 충돌, 429 요청 제한, 503 외부 준비/장애로 처리합니다.

| 영역 | 경로 | 설명 |
| --- | --- | --- |
| 공개 설정 | GET /config | 지도 공개 키와 실제 연결 상태. 서버 비밀 키 제외 |
| 인증 | POST /auth/register, /login, /logout | 가입·로그인·로그아웃 |
| 계정 | GET /auth/me, PUT /auth/profile | 기본 취향과 수신 범위 |
| 메일 | POST /auth/verify/send, /verify, /reset/send, /reset | 발송·일회용 인증·비밀번호 재설정 |
| 장소 | GET /catalog/places?region=&query= | 공개 탐색 |
| 추천 | POST /recommendations | 여행 조건 → 세 코스, 엔진/경고/숙소 추정 포함 |
| 여행 | GET/POST /trips, GET/DELETE /trips/{id} | 생성·조회·소유자 삭제 |
| 대안 | GET /trips/{id}/alternatives/{item}?rain= | 중복 제외, 선택적 실내 후보 |
| 비교/적용 | POST /trips/{id}/preview, /apply | `{itemId, placeId, version}` |
| 예약 | PUT /trips/{id}/booking, /lodging-booking | `{itemId,status,reason,fixed,version}` |
| 외부 예매 | POST /trips/{id}/external-bookings | 열차·버스·항공 등 결과 직접 기록 |
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

`NONE` 예약 전, `NEEDS_CONFIRMATION` 확인 필요, `USER_CONFIRMED` 직접 확인, `FAILED` 실패, `CANCELLED` 취소. `PARTNER_CONFIRMED`는 미래의 제휴 연동용 표시 계약이며 현재 사용자가 설정할 수 없습니다. 장소의 직접 확인 완료는 고정을 활성화합니다. 교통 기록의 출발 시간은 아직 방문 일정의 자동 고정 제약에 합쳐지지 않습니다.

## 데이터 저장

회원·여행·참여자·일정·숙소·외부 예약·초대·지출·준비물·게시물·댓글·대화·차단·신고는 정규 테이블로 관리합니다. 취향 목록과 방문 일정 이력 스냅샷만 JSON 텍스트로 저장합니다. 현재 목록 조회는 규모 제한/페이지네이션을 보강해야 합니다. 계약의 TypeScript 표현은 `frontend/src/shared/types.ts`, 실제 검증은 Java 요청 DTO가 기준입니다.
