param(
  [switch]$Live,
  [switch]$FreeTierConfirmed
)
$ErrorActionPreference = 'Stop'

# 기본 실행은 설정 유무만 확인하며 외부 API를 호출하지 않습니다.
# 실제 호출은 두 공급자 콘솔에서 무료 등급을 확인한 뒤 명시적으로 요청합니다.
# 공급자의 과금 등급을 이 스크립트에서 자동 판별할 수는 없습니다.
if ($Live -and !$FreeTierConfirmed) {
  throw '공급자 콘솔의 무료 등급을 먼저 확인하고 -Live -FreeTierConfirmed로 실행해 주세요.'
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
$settings = @{}
if (Test-Path -LiteralPath $envPath) {
  foreach ($line in Get-Content -LiteralPath $envPath -Encoding utf8) {
    if ($line -match '^(KAKAO_REST_KEY|KAKAO_JS_KEY|GEMINI_API_KEY|GEMINI_MODEL)=(.*)$') {
      $settings[$Matches[1]] = $Matches[2].Trim().Trim('"')
    }
  }
}
$model = if ($settings['GEMINI_MODEL']) { $settings['GEMINI_MODEL'] } else { 'gemini-3.5-flash-lite' }

# 원본 예외에는 헤더·응답 본문 등의 정보가 포함될 수 있어 출력하지 않습니다.
# 키 값이나 일부 문자열도 출력하지 않고 HTTP 상태와 점검 항목만 알려 줍니다.
function Get-SafeFailure($failure) {
  $response = $failure.Exception.Response
  if ($response -and $response.StatusCode) {
    $httpStatus = [int]$response.StatusCode
    $hint = switch ($httpStatus) {
      400 { '키 종류와 모델·요청 형식을 확인해 주세요.' }
      401 { '키 인증을 확인해 주세요.' }
      403 { '앱/API 활성화, 키 권한과 사용 제한을 확인해 주세요.' }
      404 { '프로젝트가 해당 모델을 사용할 수 있는지 확인해 주세요.' }
      429 { '무료 할당량과 요청 속도를 확인해 주세요. 자동 재시도하지 않습니다.' }
      default { '공급자 상태와 설정을 확인해 주세요.' }
    }
    return "HTTP $httpStatus · $hint"
  }
  return '네트워크 연결 또는 제한 시간을 확인해 주세요.'
}

$results = [Collections.Generic.List[object]]::new()
foreach ($provider in @('Kakao REST', 'Kakao 지도 SDK', 'Gemini')) {
  $settingName = switch ($provider) {
    'Kakao REST' { 'KAKAO_REST_KEY' }
    'Kakao 지도 SDK' { 'KAKAO_JS_KEY' }
    'Gemini' { 'GEMINI_API_KEY' }
  }
  $key = $settings[$settingName]
  $state = 'MISSING'
  $detail = "$settingName 입력 필요"
  if ($key) {
    $state = 'CONFIGURED'
    $detail = '설정 있음 · 실제 연결 미확인'
    if ($provider -eq 'Kakao 지도 SDK') {
      # SDK 키의 도메인 제한은 서버 HTTP 호출로 검증할 수 없습니다.
      $state = 'BROWSER_REQUIRED'
      $detail = '브라우저에서 지도 타일·마커와 도메인 설정 확인 필요'
    } elseif ($Live) {
      try {
        if ($provider -eq 'Kakao REST') {
          $searchTerm = [Uri]::EscapeDataString('서울숲')
          $response = Invoke-RestMethod -Uri "https://dapi.kakao.com/v2/local/search/keyword.json?query=$searchTerm&size=1" -Headers @{ Authorization = "KakaoAK $key" } -TimeoutSec 15
          if (!$response.documents -or !$response.documents[0].id) { throw 'Unexpected response' }
          $detail = '실제 키워드 검색 응답 확인'
        } else {
          # 실사용자 정보 없이 테스트 후보 ID 하나만 보냅니다. 자동 재시도·모델 전환은 하지 않습니다.
          $requestBody = @{
            contents = @(@{ parts = @(@{ text = 'Return a JSON array containing only the string candidate-1.' }) })
            generationConfig = @{
              responseMimeType = 'application/json'
              responseJsonSchema = @{ type = 'array'; items = @{ type = 'string'; enum = @('candidate-1') } }
              maxOutputTokens = 1024
            }
          } | ConvertTo-Json -Depth 12 -Compress
          $safeModel = [Uri]::EscapeDataString($model)
          $response = Invoke-RestMethod -Method Post -Uri "https://generativelanguage.googleapis.com/v1beta/models/${safeModel}:generateContent" -Headers @{ 'x-goog-api-key' = $key } -ContentType 'application/json' -Body $requestBody -TimeoutSec 30
          $responseText = ($response.candidates[0].content.parts | Where-Object { $_.text } | ForEach-Object { $_.text }) -join ''
          $ids = @(ConvertFrom-Json -InputObject $responseText)
          if ($ids.Count -ne 1 -or $ids[0] -ne 'candidate-1') { throw 'Unexpected response' }
          $detail = '구조화 JSON 응답 확인 · 여행 추천 화면 검증은 별도'
        }
        $state = 'VERIFIED'
      } catch {
        $state = 'FAILED'
        $detail = Get-SafeFailure $_
      }
    }
  }
  $results.Add([PSCustomObject]@{ Provider = $provider; Status = $state; Detail = $detail })
}
$results | Format-Table -AutoSize -Wrap
if ($Live -and ($results | Where-Object { $_.Status -in @('FAILED', 'MISSING') })) { exit 1 }
