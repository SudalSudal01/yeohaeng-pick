# 포트폴리오 근거와 표기 기준

기준일: 2026-10-13. 사용자 확인과 저장소 코드를 함께 검토했으며, 팀 전체 기능을 개인 성과로 바꾸지 않았습니다.

## 기여도

- 이름: 김명현. 지원 직무: 백엔드. 공개용 전화번호는 사용자가 지정했습니다.
- 제때약: 팀 프로젝트, 개발 2.5주. 본인 담당은 AI 상담 챗봇·커뮤니티·내 약 관리·결과 발표입니다.
- 여행픽: 개인 프로젝트. 기획부터 기능 연결·DB·검증·Git 관리에 AI 개발 도구를 활용했습니다.
- 5ASIS: 팀 프로젝트. 본인 담당은 메인 대시보드·Steam API 연동·결과 발표입니다. 팀 결과보고서에서도 메인 페이지 담당을 확인했습니다.

## 제때약

저장소: https://github.com/jws909/Jette-Yak

확인 커밋: `650888196de909f4c4ff3cf465ea8d221348bd30`.

- `backend/pom.xml`: Java 21, Spring MVC 5.3, MyBatis, Oracle JDBC, WAR 구성. Spring Boot로 표기하지 않습니다.
- `backend/src/main/java/com/app/chatbot/service/QuestionAnalysis.java`: 분류 JSON·엔티티·허용 검색 조건 검증, 규칙 분기.
- `backend/src/main/java/com/app/chatbot/service/MedicationChatService.java`: 질문 의도·조회·안전 응답·Gemini 호출의 순서.
- `backend/src/main/java/com/app/chatbot/controller/ChatController.java`: 입력 검사와 세션 사용자 범위.
- `backend/src/main/java/com/app/guide/service/MedicationManagementService.java`: 등록 상태·기간·ACTIVE 필터와 비교 대상.
- `backend/src/main/java/com/app/community/service/CommunityService.java`: 작성자/관리자 처리, 상태 삭제, 신고·알림 트랜잭션.
- `tools/qa/2026-10-07-site-check.md`: 408은 백엔드 assertion 수이며 408개 테스트라고 쓰지 않습니다. 프론트 74개와 실제 HTTP 32개는 해당 팀 기록의 결과입니다. 이번 작업의 재실행 결과가 아닙니다.

PWA·가족 관리·처방전 등 팀 서비스의 다른 기능은 본인 담당으로 기재하지 않았습니다. 의학적 정확성이나 안전한 복용을 보장한다는 표현도 사용하지 않습니다.

## 여행픽

저장소: https://github.com/SudalSudal01/yeohaeng-pick (비공개).

- 실제 구현 범위: `docs/status.md`.
- 검증 기록: `docs/verification.md`.
- 권한·일정 변경·외부 공급자·Oracle: `docs/architecture.md`, `docs/oracle-setup.md`.
- 최신 H2 전체 203개, 프론트 50개. Oracle 전체 195개는 이전 단계 기록이며 최신 지역 확대의 HTTP 2개는 추가로 Oracle QA에서 실행했습니다.
- 실시간 결제·객실·좌석 확인, 운영 HTTPS 배포, 다중 서버, 운영 백업 복구는 완료로 표시하지 않습니다.

## 5ASIS

저장소: https://github.com/PSK6262/Team_5asis

확인 커밋: `8e4abd00ca5035d22a5b19470d87fd0ffa8635d6`.

- `pom.xml`: Java 11, Spring MVC 5.3, JSP, MyBatis, Oracle.
- `src/main/java/com/app/controller/MainController.java`: 내부 인기 게임·Steam 할인/랜덤·로그인 프로필의 화면 전달.
- `src/main/java/com/app/service/api/impl/SteamGameServiceImpl.java`: 외부 호출·DTO 변환·표시 6개·검색 중복 및 재시도 제한·실패 시 빈 목록.
- 팀 결과보고서: 김명현의 메인 페이지 담당과 결과 발표 역할 확인. 원본에는 다른 팀원의 정보가 있어 공개 배포 폴더에 복사하지 않았습니다.

회원 인증·다른 게시판·CHZZK 연동을 본인 기여로 기재하지 않습니다. Steam API의 운영 가용성이나 성능 개선 수치는 측정하지 않았으므로 넣지 않았습니다.

## 산출물 검사

PDF는 한국어 글꼴을 포함한 8쪽이며 렌더링 후 페이지별 잘림·겹침을 확인합니다. 웹은 320px·390px·1440px에서 프로젝트 순서·모바일 넘침·연락처·상세 열기·PDF 다운로드·페이지 오류를 확인합니다. 공개 웹 서버 배포는 별도이며 로컬 미리보기 주소를 실제 운영 주소로 제시하지 않습니다.
