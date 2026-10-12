param([ValidateSet('YEOHAENG_PICK','YEOHAENG_PICK_QA','YEOHAENG_PICK_MIGRATION_QA')][string]$Schema = 'YEOHAENG_PICK')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
$fileName = switch ($Schema) {
  'YEOHAENG_PICK_QA' { 'oracle-qa.env' }
  'YEOHAENG_PICK_MIGRATION_QA' { 'oracle-migration-qa.env' }
  default { 'oracle-app.env' }
}
$credentialsPath = Join-Path $runtimeDir $fileName
if (Test-Path -LiteralPath $credentialsPath) { throw '전용 접속 파일이 이미 있습니다. 기존 계정/비밀번호를 자동 변경하지 않습니다.' }

# 설치한 로컬 Oracle의 Windows OS 인증으로 새 전용 계정만 만듭니다.
# 관리자 비밀번호를 코드/명령줄에 넣지 않으며 기존 사용자·테이블은 변경하지 않습니다.
# 생성 비밀번호는 SQL 구문에 안전한 영문/숫자/밑줄만 사용하고 콘솔에 출력하지 않습니다.
$randomBytes = New-Object byte[] 12
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($randomBytes) } finally { $random.Dispose() }
$schemaPassword = 'Yp1_' + [BitConverter]::ToString($randomBytes).Replace('-', '')
$sql = @"
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET ECHO OFF VERIFY OFF FEEDBACK OFF
CREATE USER $Schema IDENTIFIED BY "$schemaPassword" DEFAULT TABLESPACE USERS TEMPORARY TABLESPACE TEMP QUOTA 256M ON USERS;
GRANT CREATE SESSION, CREATE TABLE TO $Schema;
EXIT
"@
$result = $sql | & sqlplus -s / as sysdba 2>&1
if ($LASTEXITCODE -ne 0) {
  # SQL*Plus 오류는 실행한 CREATE USER 구문을 포함할 수 있어 그대로 출력하지 않습니다.
  throw 'Oracle 전용 계정을 생성하지 못했습니다. 로컬 OS 인증과 같은 이름의 기존 계정 존재 여부를 확인해 주세요.'
}
@("DATABASE_URL=jdbc:oracle:thin:@//localhost:1521/orcl", "DATABASE_USERNAME=$Schema", "DATABASE_PASSWORD=$schemaPassword") |
  Set-Content -LiteralPath $credentialsPath -Encoding utf8
Write-Host "Oracle 전용 계정 $Schema 생성 완료. 접속 정보는 Git 제외된 $credentialsPath 에 보관했습니다."
