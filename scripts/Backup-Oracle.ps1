#requires -Version 7.0
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$credentials = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $projectRoot '.tools/oracle-app.env') -Encoding utf8) {
  if ($line -match '^(DATABASE_URL|DATABASE_USERNAME|DATABASE_PASSWORD)=(.*)$') { $credentials[$Matches[1]] = $Matches[2] }
}
if ($credentials.DATABASE_USERNAME -ne 'YEOHAENG_PICK' -or !$credentials.DATABASE_PASSWORD) {
  throw '이 도구는 로컬 여행픽 실사용 스키마의 백업만 허용합니다.'
}
if ($credentials.DATABASE_URL -match '^jdbc:oracle:thin:@//(localhost|127\.0\.0\.1):1521/([A-Za-z0-9._-]+)$') { $serviceName = $Matches[2] }
else { throw '로컬 Oracle 접속 주소를 확인해 주세요.' }
# API가 쓰는 도중 DDL을 적용하지 않도록 여행픽 API 중지 여부부터 확인합니다.
if (Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue) { throw 'scripts/Stop-Dev.ps1로 여행픽을 중지한 뒤 백업해 주세요.' }
$backupRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '.tools/oracle-backups'))
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$dumpName = "yeohaeng-pick-$stamp.dmp"
$logName = "yeohaeng-pick-$stamp.log"
$sqlPath = $backupRoot.Replace("'", "''")
$setup = @"
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET ECHO OFF VERIFY OFF FEEDBACK OFF
DECLARE
  existing_path VARCHAR2(4000);
BEGIN
  BEGIN
    SELECT directory_path INTO existing_path FROM dba_directories WHERE directory_name='YEOHAENG_PICK_BACKUP';
    IF existing_path <> '$sqlPath' THEN raise_application_error(-20001,'Backup directory mismatch'); END IF;
  EXCEPTION WHEN NO_DATA_FOUND THEN
    EXECUTE IMMEDIATE 'CREATE DIRECTORY YEOHAENG_PICK_BACKUP AS ''$($sqlPath.Replace("'","''"))''';
  END;
END;
/
GRANT READ, WRITE ON DIRECTORY YEOHAENG_PICK_BACKUP TO YEOHAENG_PICK;
EXIT
"@
$setupOutput = $setup | & sqlplus -s / as sysdba 2>&1
if ($LASTEXITCODE) { throw '여행픽 전용 백업 디렉터리 준비 실패. 기존 디렉터리 경로 또는 로컬 OS 인증을 확인해 주세요.' }
try {
  # Export는 SYS로 실행하지 않습니다. 자신의 스키마만 내보내며 비밀번호를 프로세스 인수에 넣지 않습니다.
  # OS 관리자 인증은 위의 전용 디렉터리 준비/권한 회수에만 사용합니다.
  $start = [Diagnostics.ProcessStartInfo]::new()
  $start.FileName = (Get-Command expdp.exe -ErrorAction Stop).Source
  $start.UseShellExecute = $false
  $start.CreateNoWindow = $true
  $start.RedirectStandardInput = $true
  $start.RedirectStandardOutput = $true
  $start.RedirectStandardError = $true
  $start.Environment['NLS_LANG'] = 'AMERICAN_AMERICA.AL32UTF8'
  # Windows expdp의 암호 프롬프트는 리다이렉트된 stdin을 읽지 않습니다. 임시 parfile로 전달하고 즉시 제거합니다.
  # 전용 계정 생성 도구의 영숫자/밑줄 암호만 허용하므로 parfile 구문으로 해석될 문자를 받지 않습니다.
  if ($credentials.DATABASE_PASSWORD -notmatch '^[A-Za-z0-9_]+$') { throw '전용 계정 비밀번호 형식을 확인해 주세요.' }
  $parametersPath = Join-Path $backupRoot "export-$stamp.par"
  @("USERID=YEOHAENG_PICK/$($credentials.DATABASE_PASSWORD)@//localhost:1521/$serviceName",'SCHEMAS=YEOHAENG_PICK','DIRECTORY=YEOHAENG_PICK_BACKUP',"DUMPFILE=$dumpName","LOGFILE=$logName",'EXCLUDE=STATISTICS','REUSE_DUMPFILES=NO') | Set-Content -LiteralPath $parametersPath -Encoding ascii
  $start.ArgumentList.Add("PARFILE=$parametersPath")
  $process = [Diagnostics.Process]::Start($start)
  $outputTask = $process.StandardOutput.ReadToEndAsync()
  $errorTask = $process.StandardError.ReadToEndAsync()
  $process.StandardInput.Close()
  $process.WaitForExit()
  $outputText = $outputTask.GetAwaiter().GetResult()
  $errorText = $errorTask.GetAwaiter().GetResult()
  # Export 로그/덤프는 개인정보가 들어 있는 로컬 백업이므로 Git 제외된 경로에만 보관합니다.
  [IO.File]::WriteAllText((Join-Path $backupRoot "client-$stamp.log"), $outputText + $errorText)
  $dumpPath = Join-Path $backupRoot $dumpName
  if ($process.ExitCode -ne 0 -or !(Test-Path -LiteralPath $dumpPath) -or (Get-Item -LiteralPath $dumpPath).Length -eq 0) { throw 'Oracle Export가 완료되지 않았습니다. .tools/oracle-backups의 로컬 로그를 확인해 주세요.' }
  $manifest = @{ schema='YEOHAENG_PICK'; dumpFile=$dumpName; sha256=(Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash; bytes=(Get-Item -LiteralPath $dumpPath).Length; createdAt=(Get-Date).ToString('o') }
  $manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $backupRoot "manifest-$stamp.json") -Encoding utf8
  Copy-Item -LiteralPath (Join-Path $projectRoot '.env') -Destination (Join-Path $backupRoot "app-$stamp.env")
  Write-Host "여행픽 Oracle 백업 완료: $dumpPath"
} finally {
  if ($parametersPath -and (Test-Path -LiteralPath $parametersPath)) { Remove-Item -LiteralPath $parametersPath }
  # 평소의 앱 계정에는 파일 디렉터리 권한을 남기지 않습니다. 앱의 원래 최소 권한은 유지합니다.
  $revoke = "WHENEVER SQLERROR EXIT SQL.SQLCODE`nREVOKE READ, WRITE ON DIRECTORY YEOHAENG_PICK_BACKUP FROM YEOHAENG_PICK;`nEXIT"
  $revokeOutput = $revoke | & sqlplus -s / as sysdba 2>&1
  if ($LASTEXITCODE) { Write-Warning '백업 디렉터리의 임시 권한 회수를 확인해 주세요.' }
}
