param(
  [ValidateSet('copy','verify')][string]$Mode = 'verify',
  [string]$SourceFile,
  [string]$CredentialsFile
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$SourceFile) { $SourceFile = Join-Path $projectRoot 'backend/.data/travel' }
if (!$CredentialsFile) { $CredentialsFile = Join-Path $projectRoot '.tools/oracle-app.env' }
if (!(Test-Path -LiteralPath ($SourceFile + '.mv.db'))) { throw '원본 H2 파일이 없습니다.' }
if (!(Test-Path -LiteralPath $CredentialsFile)) { throw 'Oracle 전용 접속 정보 파일이 없습니다.' }
$libraries = Join-Path $projectRoot '.tools/oracle-libs'
if (!(Test-Path -LiteralPath $libraries)) { throw 'Oracle JDBC/Flyway 라이브러리를 .tools/oracle-libs 에 준비해 주세요. docs/oracle-setup.md 를 참고하세요.' }

# 접속 값은 자식 Java 프로세스 환경에만 전달하고 명령 인수/로그에 넣지 않습니다.
$values = @{}
foreach ($line in Get-Content -LiteralPath $CredentialsFile -Encoding utf8) {
  if ($line -match '^(DATABASE_[A-Z]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
}
$names = @('DATABASE_URL','DATABASE_USERNAME','DATABASE_PASSWORD','H2_SOURCE_FILE')
$before = @{}
foreach ($name in $names) { $before[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
  foreach ($name in $names[0..2]) { [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process') }
  [Environment]::SetEnvironmentVariable('H2_SOURCE_FILE', $SourceFile, 'Process')
  $classPath = (Join-Path $libraries '*') + [IO.Path]::PathSeparator + (Join-Path $projectRoot 'backend/target/classes')
  & java --class-path $classPath (Join-Path $projectRoot 'scripts/db/MigrateH2ToOracle.java') $Mode
  if ($LASTEXITCODE) { throw 'Oracle 데이터 이전/검증에 실패했습니다. 원본과 백업을 유지하고 원인을 해결한 뒤 다시 검증해 주세요.' }
} finally {
  foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $before[$name], 'Process') }
}
