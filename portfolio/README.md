# 김명현 백엔드 포트폴리오

지원용 PDF와 상시 공개 가능한 정적 웹을 함께 관리합니다. 순서는 **제때약 → 여행픽 → 5ASIS**이며, `content.json`을 두 형식의 공통 원본으로 사용합니다.

## 파일

- `../output/pdf/kim-myeonghyeon-portfolio.pdf`: 회사 지원에 첨부할 8쪽 PDF.
- `site/`: 웹 배포 폴더. 서버·DB·API 키 없이 HTML/CSS/JavaScript만 제공합니다.
- `../output/web/kim-myeonghyeon-portfolio-web.zip`: 정적 호스팅에 올릴 웹 파일 묶음. 압축을 풀면 `index.html`이 바로 루트에 있습니다.
- `content.json`: 공개를 허용한 프로필과 프로젝트 설명. 연락처는 사용자가 지정한 값입니다.
- `build.py`: PDF 생성, 웹 JSON 동기화, 웹 다운로드용 PDF 복사.
- `evidence.md`: 코드 근거·기여도·검증 기록의 출처와 한계.

## 로컬 미리보기

프로젝트 루트에서 실행합니다. Python이 설치된 PC에서 사용할 수 있습니다.

```powershell
python -m http.server 5200 --bind 127.0.0.1 --directory portfolio/site
```

브라우저에서 `http://127.0.0.1:5200`을 엽니다. `index.html`을 파일로 바로 열면 브라우저의 로컬 JSON 접근 제한 때문에 내용을 읽지 못할 수 있으므로 HTTP 미리보기를 사용합니다.

## PDF 재생성

Python의 `reportlab`이 필요하며 현재 작성 스크립트는 Windows의 맑은 고딕을 포함합니다. 다른 OS에서는 `build.py`의 글꼴 경로를 해당 PC의 한국어 TrueType 글꼴로 변경합니다.

```powershell
python portfolio/build.py
```

생성 후 모든 페이지를 PNG로 렌더해 글자 누락·겹침·하단 잘림을 확인합니다. 사이트와 PDF에 연락처·담당 기능·검증 수가 같은지 함께 확인합니다. 근거 없는 역할·성능 수치·운영 배포 주장은 추가하지 않습니다.

## 상시 공개

2026.10.13 배포 완료: **https://sudalsudal01.github.io/backend-portfolio/**

공개 저장소는 https://github.com/SudalSudal01/backend-portfolio 이며 `main` 루트를 GitHub Pages로 배포합니다. 여행픽 비공개 저장소의 이력은 복제하지 않고 `site/`의 공개용 파일만 게시했습니다. 사진·연락처·PDF·JSON의 공개 접속을 확인했습니다. PC나 로컬 서버를 꺼도 GitHub Pages에서 제공됩니다.

수정할 때는 이 프로젝트에서 콘텐츠와 PDF를 검증한 뒤, 공개 저장소의 정적 파일을 갱신하고 커밋·푸시합니다. 두 저장소 간 동기화는 현재 수동이며, 여행픽 저장소의 푸시만으로 공개 페이지가 갱신되지는 않습니다.

Git은 코드 관리이고 호스팅은 웹 주소를 제공하는 서비스입니다. 정적 포트폴리오를 배포하는 데 여행픽 API·Oracle 서버를 계속 켜 둘 필요가 없습니다. 현재 `5200` 주소는 이 PC에서만 보는 로컬 미리보기이며 공개 주소가 아닙니다.

가장 단순한 무료 방식은 **포트폴리오 전용 공개 저장소 + GitHub Pages**입니다. 여행픽 저장소는 비공개로 유지하고 `site/`의 공개용 파일만 새 저장소의 루트로 복사합니다. 새 저장소에서 `Settings → Pages → Deploy from a branch → main / root`를 선택하면 `https://SudalSudal01.github.io/저장소이름/` 형태의 주소가 제공됩니다. `SudalSudal01.github.io` 이름의 사용자 사이트 저장소라면 기본 주소는 `https://SudalSudal01.github.io/`입니다. 실제로 저장소와 Pages를 만들기 전에는 이 주소가 존재한다고 안내하지 않습니다.

GitHub Free의 Pages는 공개 저장소를 기준으로 제공되며 비공개 저장소의 Pages는 유료 플랜 범위입니다. 웹 소스까지 비공개로 유지하고 싶다면 개인 비공개 저장소를 Vercel 또는 Cloudflare Pages에 연결하는 방법을 검토할 수 있습니다. Vercel Hobby에는 개인/조직 저장소와 커밋 작성자 관련 제한이 있으므로 계정·저장소 형태를 확인합니다.

처음 배포할 때 GitHub/호스팅 계정 연결과 공개 범위를 확인합니다. 소스에는 `.env`, DB 덤프, 실사용자 화면, 다른 팀원의 연락처를 포함하지 않습니다. 이 포트폴리오에는 본인이 공개용으로 지정한 이름·직무·전화번호·이메일·프로필 사진을 포함했습니다.

공개 주소는 호스팅이 유지되는 동안 접속할 수 있습니다. Git에 변경을 푸시하면 자동 배포하도록 연결할 수 있고, 개인 도메인은 선택 사항입니다. 백엔드 서비스 자체를 배포하는 일은 정적 포트폴리오 배포와 별도입니다.

공식 안내:
- https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages
- https://docs.github.com/en/pages/getting-started-with-github-pages/creating-a-github-pages-site
- https://vercel.com/docs/git
- https://developers.cloudflare.com/pages/configuration/git-integration/

## 다음 검토

이번 파일은 본인 담당 기능과 코드 근거를 우선 정리한 첫 버전입니다. 회사별 제출 분량에 맞춘 축약본, 실제 팀 서비스의 개인정보 없는 스크린샷·시연 영상, 추가 문제 해결 수치와 공개 웹 주소는 확인한 자료가 있을 때 보강합니다.
