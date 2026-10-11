param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

# .env는 명령으로 실행하지 않습니다. KEY=VALUE 문자열만 현재 프로세스 환경변수에 주입합니다.
$envPath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envPath) {
  foreach ($line in Get-Content -LiteralPath $envPath -Encoding utf8) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
      [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim().Trim('"'), 'Process')
    }
  }
}
$apiPort = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 18081 }
foreach ($port in @($apiPort, 5173)) {
  if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
    throw "$port 포트가 사용 중입니다. 기존 프로세스를 확인하거나 실행 중인 여행픽을 먼저 종료해 주세요."
  }
}
$runtimeDir = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
Push-Location (Join-Path $projectRoot 'frontend')
try {
  if (!(Test-Path -LiteralPath 'node_modules')) { & npm.cmd ci; if ($LASTEXITCODE) { throw 'npm ci 실패' } }
} finally { Pop-Location }
if (!$SkipBuild) {
  Push-Location (Join-Path $projectRoot 'backend')
  try { & .\mvnw.cmd -q -DskipTests package; if ($LASTEXITCODE) { throw '백엔드 빌드 실패' } }
  finally { Pop-Location }
}
$jarPath = Join-Path $projectRoot 'backend/target/travel-api-0.1.0.jar'
if (!(Test-Path -LiteralPath $jarPath)) { throw '실행 JAR이 없습니다. -SkipBuild 없이 실행해 주세요.' }

# 새 콘솔 창을 띄우지 않고 로그를 파일로 보존합니다. 종료는 저장한 PID만 대상으로 합니다.
# Oracle의 javapath 실행기는 별도 자식 JVM을 만들 수 있습니다. 실제 JVM을 직접 실행해 PID를 정확히 보존합니다.
$javaSettings = & java -XshowSettings:properties -version 2>&1
$javaHomeLine = $javaSettings | Where-Object { "$_" -match '^\s*java.home\s*=' } | Select-Object -First 1
if (!$javaHomeLine) { throw 'Java 실행 경로를 확인할 수 없습니다.' }
$javaRuntimeHome = ("$javaHomeLine" -split '=', 2)[1].Trim()
$javaExe = Join-Path $javaRuntimeHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $javaExe)) { throw 'Java 실행 파일을 찾을 수 없습니다.' }
$apiProcess = Start-Process -FilePath $javaExe -ArgumentList @('-jar', ('"'+$jarPath+'"')) -WorkingDirectory (Join-Path $projectRoot 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir 'api.log') -RedirectStandardError (Join-Path $runtimeDir 'api-error.log')
$vitePath = Join-Path $projectRoot 'frontend/node_modules/vite/bin/vite.js'
$webProcess = Start-Process -FilePath 'node' -ArgumentList @(('"'+$vitePath+'"'),'--host','127.0.0.1') -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir 'web.log') -RedirectStandardError (Join-Path $runtimeDir 'web-error.log')
@{apiPid=$apiProcess.Id; webPid=$webProcess.Id; apiStarted=$apiProcess.StartTime.ToString('o');webStarted=$webProcess.StartTime.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimeDir 'run-state.json') -Encoding utf8
Write-Host '여행픽을 시작했습니다: http://127.0.0.1:5173'
Write-Host '백엔드 첫 실행은 잠시 걸립니다. .tools/api.log에서 준비 상태를 확인할 수 있습니다.'
