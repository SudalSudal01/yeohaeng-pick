# Gmail 발송 계정 연결

이메일 주소만으로는 인증 메일을 보낼 수 없습니다. 현재 서버의 SMTP 방식은 별도의 인증 정보가 필요합니다. 개인 이메일과 비밀번호는 Git에 포함하지 않고 루트 `.env`에만 입력합니다.

## 로컬 설정 순서

1. 사용할 Google 계정의 [2단계 인증과 앱 비밀번호 안내](https://support.google.com/accounts/answer/185833?hl=ko)를 확인합니다. 계정 보안 설정과 앱 비밀번호 생성은 계정 소유자가 직접 진행합니다. 계정 종류나 보안 설정에 따라 앱 비밀번호 메뉴가 없을 수 있습니다.
2. 앱 비밀번호를 발급할 수 있는 계정이면, 여행픽 전용 앱 비밀번호를 로컬 `.env`의 `SMTP_PASSWORD`에 입력합니다. 일반 Google 로그인 비밀번호를 입력하지 않습니다. 발급 화면에서 표시하는 구분용 공백은 제외합니다.
3. `MAIL_FROM`과 `SMTP_USERNAME`은 동일한 발송 계정 주소를 사용합니다. 서버는 `SMTP_HOST=smtp.gmail.com`, `SMTP_PORT=587`과 STARTTLS를 사용합니다. [Google SMTP 문서](https://developers.google.com/workspace/gmail/imap/imap-smtp)를 참고하세요.
4. 인증 정보를 모두 입력한 다음 `MAIL_ENABLED=true`로 변경하고 개발 서버를 재시작합니다.
5. 여행픽에 로그인한 뒤 마이페이지에서 인증 메일을 요청합니다. 메일 수신 → 링크 열기 → 인증 버튼 누르기 → 인증 상태 갱신을 확인합니다. 발송 설정만 완료한 상태와 실제 수신 확인을 구분합니다.

```powershell
.\scripts\Stop-Dev.ps1
.\scripts\Start-Dev.ps1 -SkipBuild
```

현재 앱은 Gmail OAuth 발송을 구현하지 않았습니다. 앱 비밀번호를 사용할 수 없는 계정이면 OAuth 발송이나 다른 SMTP 공급자 연결이 필요합니다. 지도와 AI 서비스는 Gmail 발송과 별개이며 각각 Kakao/Gemini API 키 설정이 필요합니다.
