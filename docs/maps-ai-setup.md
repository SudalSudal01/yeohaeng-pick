# Kakao 지도·장소 검색과 Gemini 연결

2026-10-12 공식 문서를 기준으로 정리했습니다. 키 입력은 루트 `.env`에서 진행하고 채팅·스크린샷·Git에 키를 포함하지 않습니다. 기존 SMTP 설정은 유지합니다.

## Kakao

1. [Kakao Developers](https://developers.kakao.com/)에서 로그인하고 여행픽용 앱을 생성합니다. 계정 등록·약관 동의·키 발급은 계정 소유자가 진행합니다.
2. 앱의 **플랫폼 키**에서 REST API 키와 JavaScript 키를 확인합니다. `.env`의 `KAKAO_REST_KEY`에는 REST API 키, `KAKAO_JS_KEY`에는 JavaScript 키를 입력합니다. Admin 키는 사용하지 않습니다.
3. JavaScript 키의 SDK 도메인에 **`http://127.0.0.1:5173`**을 등록합니다. `localhost`로도 접속하려면 `http://localhost:5173`을 별도로 등록합니다. 현재 개발 서버의 접속 주소는 첫 번째 주소입니다.
4. 앱의 **카카오맵 → 사용 설정 → 상태 ON**과 **카카오맵 무료 쿼터 배지**를 확인합니다. [카카오맵 사용 안내](https://developers.kakao.com/docs/ko/kakaomap/common)에 따르면 2026-07-21부터 개발자 계정에서 처음 활성화한 앱에만 무료 쿼터가 제공됩니다. 기존 무료 앱이 있다면 해당 앱에 여행픽 개발 도메인을 추가해 사용할 수 있는지 먼저 검토합니다. 두 번째 앱에서 유료 API·결제 수단을 등록해 진행하지 않습니다.
5. 서버의 장소 검색과 브라우저의 지도 표시를 각각 확인합니다. REST 검색이 성공해도 JavaScript 키·도메인 설정 문제로 지도가 실패할 수 있습니다.

REST 키는 백엔드만 사용합니다. JavaScript 키는 지도 SDK 로드에 필요해 브라우저에 공개되므로 등록 도메인으로 사용 범위를 제한합니다. 이 키로는 지도 리뷰 본문·객실 재고·교통 요금이 제공되지 않습니다. 장소 리뷰는 해당 카카오맵 상세 페이지로 연결합니다.

참고: [지도 Web API 준비하기](https://apis.map.kakao.com/web/guide/), [장소 검색 REST API](https://developers.kakao.com/docs/ko/local/dev-guide).

## Gemini

1. [Google AI Studio의 API 키](https://aistudio.google.com/api-keys)에서 여행픽용 프로젝트와 키를 준비합니다. **프로젝트의 등급이 Free Tier인지 확인**하고, 결제 연결·유료 등급 전환은 하지 않습니다.
2. 키를 `.env`의 `GEMINI_API_KEY`에 입력합니다. REST 헤더의 `x-goog-api-key`로만 전송하며 브라우저에는 전달하지 않습니다.
3. `GEMINI_MODEL=gemini-3.5-flash-lite`와 `AI_DAILY_LIMIT=20`을 사용합니다. 신규 프로젝트의 Gemini 2.5 접근 제한을 반영해 기본 모델을 변경했습니다. [모델 목록](https://ai.google.dev/gemini-api/docs/models)과 [가격·무료 등급](https://ai.google.dev/gemini-api/docs/pricing)에서 해당 프로젝트의 지원 여부를 다시 확인합니다.
4. AI Studio에서 모델별 실제 한도를 확인합니다. 앱의 하루 20회 제한은 단일 서버 프로세스의 제한이고 공급자의 무료 한도를 보장하지 않습니다. 한도 초과 시 유료 모델로 자동 전환하지 않고 규칙 추천을 사용합니다.

AI에는 조회된 장소 ID·분류·지역과 여행 취향만 보냅니다. 이름·이메일·개인 대화·예약 번호는 보내지 않습니다. [구조화 출력](https://ai.google.dev/gemini-api/docs/structured-output)으로 후보 ID 목록을 받고 서버에서 허용된 ID인지 다시 검사합니다. AI가 요금·재고·영업시간을 만들어 내는 데 사용하지 않습니다.

참고: [키 관리](https://ai.google.dev/gemini-api/docs/api-key), [등급·결제](https://ai.google.dev/gemini-api/docs/billing).

## 설정 후 검증

다음 명령은 설정 유무만 보여 주고 외부 API를 호출하지 않습니다. 키 값·원본 오류 본문은 출력하지 않습니다.

```powershell
.\scripts\Test-Integrations.ps1
```

두 공급자 콘솔의 무료 등급 확인 후에만 실제 연결 점검을 실행합니다. Kakao 검색 1회와 테스트 후보 ID 하나를 사용하는 Gemini 요청 1회를 보냅니다. SDK 키는 브라우저에서 별도로 확인해야 합니다. 이 점검의 Gemini 호출은 앱 프로세스의 하루 호출 카운터에 포함되지 않습니다.

```powershell
.\scripts\Test-Integrations.ps1 -Live -FreeTierConfirmed
```

키를 입력했다고 연결 완료로 표시하지 않습니다. 서버 재시작 후 실제 장소 검색 결과의 출처가 `KAKAO`인지, 브라우저에 지도 타일과 마커가 표시되는지, 새 여행의 추천 엔진이 `GEMINI`인지 각각 확인합니다. 이전에 저장한 예시 여행은 자동으로 실제 장소 여행으로 변경하지 않습니다.

검증 결과는 [검증 기록](verification.md)에 추가합니다. 실제 키가 입력되기 전에는 기존 로컬 예시 모드로 동작합니다.
