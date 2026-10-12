# Oracle 연결과 기존 데이터 이전

사용자 요청에 따라 실제 로컬 여행픽 저장소를 Oracle 19c로 전환합니다. H2는 설치 없이 실행하는 개발 모드와 기본 자동 테스트용으로 남깁니다. 기존 H2/PostgreSQL용 V1~V11 파일은 수정하지 않고 `db/oracle`의 동일 버전 이력을 별도로 사용합니다.

## SQL Developer 접속

| 항목 | 값 |
| --- | --- |
| 접속 이름 | 여행픽 |
| 데이터베이스 유형 / 인증 / 롤 / 접속 유형 | Oracle / 기본값 / 기본값 / 기본 |
| 사용자 이름 | `YEOHAENG_PICK` |
| 비밀번호 | 로컬 `.tools/oracle-app.env`의 `DATABASE_PASSWORD` 값 |
| 호스트 / 포트 | `localhost` / `1521` |
| SID | `ORCL` |

서비스 이름 방식이라면 `orcl`도 사용할 수 있습니다. 이 PC에서 리스너·서비스와 non-CDB 구성을 확인한 값이며 다른 PC에서는 해당 설치의 값으로 바꿉니다. 앱은 `jdbc:oracle:thin:@//localhost:1521/orcl`을 사용합니다. 접속 후 아래 읽기 전용 쿼리로 여행픽 스키마를 확인할 수 있습니다.

```sql
SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS connected_schema FROM dual;
SELECT table_name FROM user_tables ORDER BY table_name;
SELECT COUNT(*) AS trip_count FROM trips;
```

## DB 설명과 스키마 업데이트

V12/V13부터 실제 DB 사전에 한국어 코멘트를 저장합니다. V16 여행 공동 투표까지 실제 Oracle의 도메인 테이블 **25개·컬럼 199개**를 확인했습니다. `trip_polls`, `trip_poll_options`, `trip_poll_votes` 3개 테이블과 18개 컬럼에는 역할, KST 시간 기준, 상태 값, NULL 의미와 개별 선택 비공개 범위를 `COMMENT ON TABLE/COLUMN`으로 작성했고 누락은 0건입니다. SQL Developer에서 테이블을 펼친 뒤 **열(Columns)의 Comments** 또는 테이블 설명을 확인할 수 있습니다.

```sql
SELECT table_name, comments FROM user_tab_comments
 WHERE table_type='TABLE' AND table_name<>'flyway_schema_history' ORDER BY table_name;
SELECT column_name, comments FROM user_col_comments
 WHERE table_name='REPORTS' ORDER BY column_name;
```

이 설명은 SQL 파일 안의 `--`와 별도로 저장되는 [Oracle COMMENT 메타데이터](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/COMMENT.html)입니다. 적용한 이전 Flyway 파일은 수정하지 않고 V16 새 파일로 투표 구조와 설명을 추가했으며, V17은 투표 복합 외래키 인덱스만 보강합니다. 신고 증거를 추가한 V13도 신규 컬럼 설명을 함께 제공합니다.

## 현재 Oracle 데이터 백업

PowerShell 7에서 아래 순서로 실행합니다. Oracle 서비스 자체와 다른 프로젝트는 중지하지 않습니다.

```powershell
.\scripts\Stop-Dev.ps1
.\scripts\Backup-Oracle.ps1
.\scripts\Start-Dev.ps1 -SkipBuild
```

`Backup-Oracle.ps1`은 로컬 `YEOHAENG_PICK` 스키마만 Data Pump Export합니다. Windows OS 관리자 인증은 여행픽 전용 디렉터리 확인/생성·임시 READ/WRITE 부여/회수에만 사용하고 Export는 앱 자신의 계정으로 실행합니다. [공식 Data Pump 설명](https://docs.oracle.com/en/database/oracle/oracle-database/19/sutil/oracle-data-pump-export-utility.html)에 맞춰 SYSDBA Export를 사용하지 않습니다. 평소 앱 권한은 CREATE SESSION/CREATE TABLE로 유지합니다.

비밀번호는 명령줄에 넣지 않고 Git 제외된 임시 parfile로 전달한 뒤 finally에서 제거합니다. `.tools/oracle-backups/`에 전용 스키마 덤프, Export 로그, SHA-256/크기 manifest, 환경설정 사본을 보관합니다. 실제 사용자 정보와 접속 정보가 있으므로 이 디렉터리는 Git에 올리거나 공개 공유하지 않습니다. 다른 경로를 가리키는 동명 Oracle DIRECTORY가 있으면 덮어쓰지 않고 중단합니다.

2026-10-12 V13 적용 전 Export가 정상 완료됐고 적용 전후 20개 테이블의 모든 기존 열/PK 순서 체크섬이 일치했습니다. 이전 신고의 신규 메타데이터는 모두 NULL입니다. 덤프를 새 스키마에 Import하여 복구하는 훈련과 정기 보관/파기, 오프라인 보관은 후속입니다. 이 로컬 논리 백업이 운영 복구 계획 전체를 대체하지 않습니다.

V14 적용 전에도 새 Export를 생성했고 적용 전후 기존 20개 테이블의 모든 기존 열 체크섬이 일치했습니다. 새 `trip_imports`는 빈 테이블로 시작했으며 원본 데이터와 소유권을 수정하지 않았습니다. 실제 DB 사전에서 21개 테이블/177개 컬럼의 코멘트 누락이 없음을 확인했습니다.

V15 적용 전 `yeohaeng-pick-20261012-223155-549.dmp`를 새로 Export했습니다. 적용 전후 기존 21개 테이블의 모든 열/값 체크섬이 일치했습니다. `favorite_places`를 추가했으며 22개 테이블/181개 컬럼의 한국어 코멘트 누락 0건을 확인했습니다.

V16 적용 전 `yeohaeng-pick-20261013-033541-693.dmp`를 새로 Export한 뒤 실제 `YEOHAENG_PICK`을 V15에서 V16으로 올렸습니다. 적용 전후 기존 22개 테이블의 지문은 모두 같고, 새 질문·후보·표는 각각 0건으로 시작했습니다. 실제 DB 사전에서 25개 테이블/199개 컬럼과 한국어 코멘트 누락 0건을 확인했습니다. Oracle QA의 `TripPollFlowTest` 12개와 전체 186개가 통과했고, 실제 앱 API 200 응답을 확인했습니다. 아래 쿼리로 현재 수와 투표 코멘트를 다시 확인할 수 있습니다.

V17 적용 전에는 `yeohaeng-pick-20261013-041035-467.dmp`를 추가 Export했습니다. 격리 Oracle QA 전체 186개를 먼저 통과한 뒤 실제 스키마를 V16→V17로 올렸고, `IDX_POLL_VOTES_OPTION`의 열 순서가 `POLL_ID` 1번·`OPTION_ID` 2번인지 확인했습니다. 도메인 테이블 25개·컬럼 199개와 빈 투표 3개 테이블의 행 수는 그대로이며 재기동한 웹 5173·API 18081은 200을 반환했습니다.

```sql
SELECT COUNT(*) AS domain_table_count FROM user_tables
 WHERE table_name <> 'flyway_schema_history';
SELECT COUNT(*) AS domain_column_count FROM user_tab_columns
 WHERE table_name IN (SELECT table_name FROM user_tables)
   AND table_name <> 'flyway_schema_history';
SELECT table_name, comments FROM user_tab_comments
 WHERE table_name IN ('TRIP_POLLS','TRIP_POLL_OPTIONS','TRIP_POLL_VOTES')
 ORDER BY table_name;
SELECT index_name, column_name, column_position FROM user_ind_columns
 WHERE index_name='IDX_POLL_VOTES_OPTION' ORDER BY column_position;
```

기존 H2→Oracle 전환 도구는 검증 당시의 **V11 원본→빈 V11 대상**으로 고정했습니다. QA fixture도 V11을 명시합니다. 이후 버전의 데이터를 이 도구로 덮어쓰거나 병합하지 않습니다. V16은 Flyway가 기존 Oracle 스키마에 새 빈 투표 테이블을 추가했으며, 기존 데이터 보호는 적용 전 Data Pump 백업과 22개 테이블 지문 대조로 확인했습니다. V17은 적용된 V16을 변경하지 않고 자식 표의 `(poll_id,option_id)` 인덱스만 추가합니다.

전용 계정은 자신의 테이블만 관리하도록 `CREATE SESSION`, `CREATE TABLE`, `USERS` 테이블스페이스의 256MB 할당량만 부여합니다. 애플리케이션에서 SYS/SYSTEM/DBA 계정을 사용하지 않습니다. 기존 다른 프로젝트 계정과 테이블은 변경하지 않습니다.

## 새 PC에서 전용 계정 만들기

로컬 Oracle에 대한 Windows OS 관리자 인증이 가능한 환경에서 실행합니다. CDB 설치라면 사용할 PDB의 관리자 연결로 계정을 별도 생성해야 하며, 이 non-CDB 전용 스크립트를 그대로 실행하지 않습니다.

```powershell
.\scripts\New-OracleSchema.ps1
.\scripts\New-OracleSchema.ps1 -Schema YEOHAENG_PICK_QA
.\scripts\New-OracleSchema.ps1 -Schema YEOHAENG_PICK_MIGRATION_QA
```

각 스크립트는 임의 비밀번호를 생성해 Git 제외된 `.tools/oracle-app.env`, `oracle-qa.env`, `oracle-migration-qa.env`에 보관합니다. 기존 파일/계정을 자동으로 덮어쓰거나 비밀번호를 재설정하지 않습니다. 접속 파일은 개인 로컬 비밀 파일이며 백업이나 공유 시에도 보호해야 합니다.

## 실행 설정

실제 프로젝트 `.env`에 아래 항목을 설정합니다. 기존 지도·AI·메일 설정은 유지합니다.

```dotenv
SPRING_PROFILES_ACTIVE=local,oracle
DATABASE_URL=jdbc:oracle:thin:@//localhost:1521/orcl
DATABASE_USERNAME=YEOHAENG_PICK
DATABASE_PASSWORD=전용_계정_비밀번호
```

`Start-Dev.ps1`이 파일을 환경변수로 읽습니다. 직접 Maven을 실행할 때는 같은 환경변수를 주입해야 합니다. 공개 운영은 `prod,oracle`과 HTTPS 구성이 별도로 필요하며 이번 전환이 운영 배포 검증까지 의미하지는 않습니다.

## Oracle 차이와 API 계약

- SQL BOOLEAN을 지원하지 않는 19c에서는 `NUMBER(1)` + `CHECK (열 IN (0,1))`로 저장합니다. MyBatis의 `databaseId=oracle`이 명시된 SQL만 0/1 리터럴을 사용하며 Java/JSON에서는 기존 Boolean을 유지합니다.
- 긴 JSON·게시글·메시지는 CLOB으로 보관합니다. 한글/이모지 2,000자는 VARCHAR2의 바이트 제한을 넘을 수 있어 댓글과 메시지도 CLOB을 사용합니다.
- 긴 본문은 `LongTextTypeHandler`가 Oracle에서 `setClob(Reader)`로 바인딩합니다. 이 PC의 19.3/JDBC 조합에서 96,011자 한글/이모지 JSON을 `setString`/`setCharacterStream`으로 저장하면 8,192자 청크 경계의 이모지가 손상되는 사례를 재현했고, 명시적 CLOB 바인딩으로 해결했습니다. 이력 Map 조회와 경로 String 목록 조회를 실제 DB에서 다시 대조하는 회귀 검사를 추가했습니다.
- Oracle은 빈 문자열을 NULL로 처리합니다. 원래 빈 값을 허용한 열만 nullable로 만들고 MyBatis 결과에서 `""`로 복원합니다. 날짜·미입력 비용·삭제 시각의 NULL, 명시적인 0원은 구분합니다. 탈퇴한 공개 본문의 NULL 역시 삭제된 내용입니다.
- Oracle의 대문자 열 이름을 camelCase로 변환하고, NUMBER 정수와 5개 달력 날짜 열을 기존 API 타입으로 정규화합니다. TIMESTAMP의 시각은 날짜 변환 대상에서 제외합니다. NULL 숫자 바인딩은 JDBC 타입을 명시해 `COALESCE` 타입 오류를 방지합니다.
- 행 제한은 ANSI `FETCH FIRST ... ROWS ONLY`를 사용합니다. 기존 여행/게시글의 `FOR UPDATE`, 버전 조건부 쓰기와 트랜잭션 경계는 유지합니다.

## H2 → Oracle 이전 절차

이전은 자동 앱 시작과 분리한 일회성 작업입니다. **원본을 중지하고 백업한 뒤, 빈 전용 스키마에서만** 실행합니다.

1. `Stop-Dev.ps1`로 여행픽만 중지하고 `backend/.data/travel.mv.db`와 `.env`를 `.tools/db-backups/`의 새 날짜 폴더에 백업합니다.
2. 백엔드를 패키징하고 이전 도구의 JDBC/Flyway 의존성을 준비합니다.

```powershell
cd backend
.\mvnw.cmd -DskipTests package
.\mvnw.cmd dependency:copy-dependencies -DincludeScope=runtime "-DoutputDirectory=C:/SudalSudal/project/tripPlanner/.tools/oracle-libs"
cd ..
.\scripts\Invoke-OracleMigration.ps1 -Mode copy
.\scripts\Invoke-OracleMigration.ps1 -Mode verify
```

3. 검증 성공 후에만 `.env`를 Oracle로 전환하고 `Start-Dev.ps1 -SkipBuild`로 시작합니다. 세션은 서버 메모리에 있어 다시 로그인해야 합니다. 기존 회원 비밀번호와 인증 여부·여행 ID·공유 토큰·공동 기록은 데이터 이전 대상입니다.

도구는 V11의 전체 20개 도메인 테이블/열을 검사하고, FK 부모부터 바인딩으로 삽입한 뒤 행 수·PK 정렬·전체 값 SHA-256을 대조합니다. 모든 테이블이 일치해야 한 번 커밋하고 새 연결로 다시 검증합니다. 생성 시각·좌표·빈 금액·0원·본문까지 대상이며 Oracle의 알려진 빈 문자열/NULL 차이만 정규화합니다. Flyway 이력은 Oracle 파일로 생성하므로 H2 이력 행을 복사하지 않습니다. 출력에는 원문/비밀번호를 남기지 않습니다.

대상에 한 행이라도 있으면 재복사·덮어쓰기·병합을 거절합니다. 삽입 중 실패하면 전체 DML을 롤백합니다. Oracle DDL은 트랜잭션으로 취소되지 않으므로 스키마 생성 자체와 데이터 이전은 별도입니다. 실패 시 생성된 빈 테이블은 남을 수 있고, 성공한 이전에는 단순 재실행 대신 `verify`를 사용합니다.

원본 H2 파일과 이전 전 백업은 보존합니다. **Oracle에서 새 데이터가 생긴 이후 H2로 되돌리면 그 변경은 자동 반영되지 않습니다.** 복구는 서버 중지·백업 확보 후 어느 DB를 기준으로 할지 결정하고 진행해야 합니다. 이번 도구는 역방향/증분 동기화를 제공하지 않습니다.

## 검증

`Test-Oracle.ps1`은 실사용 스키마를 거절하고 로컬 `YEOHAENG_PICK_QA`에서 기존 HTTP 시나리오를 재사용합니다. 외부 AI/관광/메일은 끄고 합성 계정으로 인증·여행·예약·협업·게시글·대화·탈퇴·동시 요청을 검사합니다. 기본 Maven 테스트는 계속 별도 H2 메모리 DB를 사용합니다.

`scripts/Test-OracleMigration.ps1`은 `VerifyOracleMigration.java`를 컴파일해 `YEOHAENG_PICK_MIGRATION_QA`와 새 합성 H2 파일에서 20개 테이블, 대용량 CLOB·한글/이모지·날짜·좌표·NULL/0/빈 문자열, 중간 실패 롤백과 재실행 거절을 검사합니다. 성공한 합성 데이터만 전체 해시 확인 후 FK 순서로 정리하며 실패 데이터는 분석할 수 있도록 남깁니다. 실사용 스키마를 거절합니다.

```powershell
.\scripts\Test-Oracle.ps1
.\scripts\Test-OracleMigration.ps1
```

Oracle 테스트는 로컬 DB를 실행하고 앞서 안내한 전용 계정·라이브러리와 컴파일된 백엔드가 준비된 환경에서 실행합니다. 일반 CI의 기본 H2 검사는 Oracle 설치 없이 실행할 수 있습니다. 최신 실제 결과는 [검증 기록](verification.md)을 참고하세요.

참고: [Oracle SQL Developer 접속](https://docs.oracle.com/en/database/oracle/oracle-database/tutorial-create-conn/index.html), [Oracle의 NULL과 빈 문자열](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/Nulls.html), [Flyway Oracle JDBC/전용 모듈](https://documentation.red-gate.com/flyway/reference/database-driver-reference/oracle-database).
