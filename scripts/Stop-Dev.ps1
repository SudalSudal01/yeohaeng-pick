$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$statePath = Join-Path $projectRoot '.tools/run-state.json'
if (!(Test-Path -LiteralPath $statePath)) { Write-Host '이 스크립트로 실행한 프로세스 기록이 없습니다.'; exit }
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
foreach ($kind in @('api','web')) {
  $processId = $state."${kind}Pid"
  $started = [datetime]$state."${kind}Started"
  $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
  # PID가 재사용되어 다른 프로그램을 가리키는 경우를 막기 위해 생성 시각과 명령줄도 검증합니다.
  if ($process -and [Math]::Abs(($process.StartTime-$started).TotalSeconds) -lt 1) {
    $command = (Get-CimInstance Win32_Process -Filter "ProcessId=$processId").CommandLine
    if ($command -and $command.Contains($projectRoot)) {
      # 이전 실행 기록이 javapath 실행기를 가리키는 경우에도 해당 프로젝트의 자식 JVM만 종료합니다.
      Get-CimInstance Win32_Process -Filter "ParentProcessId=$processId" | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains($projectRoot)
      } | ForEach-Object { Stop-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }
      Stop-Process -Id $processId -ErrorAction SilentlyContinue
    }
  }
}
Write-Host '기록된 여행픽 프로세스의 종료를 확인했습니다.'
