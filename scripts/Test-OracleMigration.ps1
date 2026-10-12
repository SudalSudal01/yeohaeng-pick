$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$credentials = Join-Path $projectRoot '.tools/oracle-migration-qa.env'
if (!(Test-Path -LiteralPath $credentials)) { throw 'New-OracleSchema.ps1 -Schema YEOHAENG_PICK_MIGRATION_QA 로 이전 검증 전용 계정을 먼저 만들어 주세요.' }
$values = @{}
foreach ($line in Get-Content -LiteralPath $credentials -Encoding utf8) {
  if ($line -match '^(DATABASE_[A-Z]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
}
if ($values.DATABASE_USERNAME -ne 'YEOHAENG_PICK_MIGRATION_QA') { throw '실사용/HTTP QA 계정에는 이전 검증을 실행할 수 없습니다.' }
$libraries = Join-Path $projectRoot '.tools/oracle-libs/*'
$classes = Join-Path $projectRoot '.tools/oracle-migration-classes'
$classpath = $libraries + [IO.Path]::PathSeparator + (Join-Path $projectRoot 'backend/target/classes')
& javac --release 21 -cp $classpath -d $classes (Join-Path $projectRoot 'scripts/db/MigrateH2ToOracle.java') (Join-Path $projectRoot 'scripts/db/VerifyOracleMigration.java')
if ($LASTEXITCODE) { throw '데이터 이전 검증 도구 컴파일 실패' }
$fixture = Join-Path $projectRoot ('.tools/oracle-migration-fixture-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$names = @('DATABASE_URL','DATABASE_USERNAME','DATABASE_PASSWORD','H2_SOURCE_FILE')
$before = @{}
foreach ($name in $names) { $before[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
  foreach ($name in $names[0..2]) { [Environment]::SetEnvironmentVariable($name,$values[$name],'Process') }
  [Environment]::SetEnvironmentVariable('H2_SOURCE_FILE',(Join-Path $fixture 'travel'),'Process')
  # 성공한 합성 데이터는 전체 해시 검증 후 정리해 다시 실행할 수 있습니다.
  # 실패 데이터는 분석할 수 있도록 남기며 실제 계정에 대한 자동 정리는 없습니다.
  & java -cp ($classpath + [IO.Path]::PathSeparator + $classes) VerifyOracleMigration
  if ($LASTEXITCODE) { throw 'Oracle 합성 데이터 이전 검증 실패' }
} finally {
  foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$before[$name],'Process') }
}
