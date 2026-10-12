param([ValidatePattern('^[A-Za-z0-9_*.,]+$')][string]$Tests = '*Test')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$file = Join-Path $projectRoot '.tools/oracle-qa.env'
if (!(Test-Path -LiteralPath $file)) { throw '먼저 New-OracleSchema.ps1 -Schema YEOHAENG_PICK_QA 로 격리 계정을 만들어 주세요.' }
$values = @{}
foreach ($line in Get-Content -LiteralPath $file -Encoding utf8) {
  if ($line -match '^(DATABASE_[A-Z]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
}
if ($values.DATABASE_USERNAME -ne 'YEOHAENG_PICK_QA') { throw '실사용 스키마에는 테스트를 실행할 수 없습니다.' }
$names = @('TEST_ORACLE_URL','TEST_ORACLE_USERNAME','TEST_ORACLE_PASSWORD')
$before = @{}
foreach ($name in $names) { $before[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
  [Environment]::SetEnvironmentVariable($names[0], $values.DATABASE_URL, 'Process')
  [Environment]::SetEnvironmentVariable($names[1], $values.DATABASE_USERNAME, 'Process')
  [Environment]::SetEnvironmentVariable($names[2], $values.DATABASE_PASSWORD, 'Process')
  Push-Location (Join-Path $projectRoot 'backend')
  try { & .\mvnw.cmd --batch-mode test "-Dtest=$Tests"; if ($LASTEXITCODE) { throw 'Oracle QA 테스트 실패' } }
  finally { Pop-Location }
} finally {
  foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $before[$name], 'Process') }
}
