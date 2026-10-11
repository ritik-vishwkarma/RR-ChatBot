
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [switch]$SkipSlowLoginRateLimitCheck
)

# Load HTTP types required by Windows PowerShell 5.1.
Add-Type -AssemblyName System.Net.Http -ErrorAction Stop
$ErrorActionPreference = 'Stop'
$script:BaseUrl = $BaseUrl.TrimEnd('/')
$script:Results = New-Object System.Collections.Generic.List[object]
$script:StartedAt = Get-Date
$script:RunId = Get-Date -Format 'yyMMddHHmmss'

function Add-Result {
    param(
        [string]$Id,
        [string]$Name,
        [ValidateSet('PASS', 'FAIL', 'SKIP', 'BLOCKED')][string]$Status,
        [string]$Details
    )
    $script:Results.Add([pscustomobject]@{
        Id = $Id
        Status = $Status
        Test = $Name
        Details = $Details
        Timestamp = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    }) | Out-Null
    $color = switch ($Status) {
        'PASS' { 'Green' }
        'FAIL' { 'Red' }
        'BLOCKED' { 'Yellow' }
        default { 'DarkYellow' }
    }
    Write-Host ("{0,-7} {1,-6} {2} — {3}" -f $Status, $Id, $Name, $Details) -ForegroundColor $color
}

function Test-Assert {
    param([string]$Id, [string]$Name, [bool]$Passed, [string]$SuccessDetails, [string]$FailureDetails)
    if ($Passed) { Add-Result $Id $Name 'PASS' $SuccessDetails }
    else { Add-Result $Id $Name 'FAIL' $FailureDetails }
    return $Passed
}

function New-ClientContext {
    $jar = [System.Net.CookieContainer]::new()
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.CookieContainer = $jar
    $handler.UseCookies = $true
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(20)
    return [pscustomobject]@{ Jar = $jar; Handler = $handler; Client = $client; Csrf = $null }
}

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)][object]$Context,
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [AllowNull()][object]$Body = $null,
        [hashtable]$Headers = @{}
    )
    $uri = [Uri]($script:BaseUrl + $Path)
    $response = $null
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method), $uri)

    if ($Path -eq '/') {
        [void]$request.Headers.Accept.ParseAdd('text/html')
    } else {
        [void]$request.Headers.Accept.ParseAdd('application/json')
    }

    if ($null -ne $Body) {
        $json = ConvertTo-Json -InputObject $Body -Depth 20 -Compress
        $request.Content = [System.Net.Http.StringContent]::new(
            $json, [System.Text.Encoding]::UTF8, 'application/json')
    }

    foreach ($key in $Headers.Keys) {
        [void]$request.Headers.TryAddWithoutValidation([string]$key, [string]$Headers[$key])
    }

    try {
        $response = $Context.Client.SendAsync($request).GetAwaiter().GetResult()
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()

        $setCookieHeaders = @()
        if ($response.Headers.Contains('Set-Cookie')) {
            $setCookieHeaders = @($response.Headers.GetValues('Set-Cookie'))
        }

        $parsed = $null
        if (-not [string]::IsNullOrWhiteSpace($raw)) {
            try { $parsed = ConvertFrom-Json -InputObject $raw -ErrorAction Stop }
            catch { $parsed = $null }
        }

        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            Body = $parsed
            Raw = $raw
            SetCookieHeaders = $setCookieHeaders
        }
    }
    finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
    }
}

function Set-Csrf {
    param([object]$Context)

    $response = Invoke-Api -Context $Context -Method 'GET' -Path '/api/v1/csrf'
    if ($response.Status -ne 200 -or
        $null -eq $response.Body -or
        $null -eq $response.Body.data -or
        [string]::IsNullOrWhiteSpace([string]$response.Body.data.token)) {
        throw "CSRF bootstrap failed (HTTP $($response.Status)); verify SecurityConfig and GET /api/v1/csrf."
    }

    $Context.Csrf = [string]$response.Body.data.token
    $cookies = $Context.Jar.GetCookies(
        [Uri]($script:BaseUrl + '/api/v1/chats')
    )
    $cookie = $cookies['XSRF-TOKEN']
    $matches = $null -ne $cookie -and
        $Context.Csrf -ceq [string]$cookie.Value
    return $response
}

function Invoke-Unsafe {
    param(
        [object]$Context,
        [string]$Method,
        [string]$Path,
        [AllowNull()][object]$Body = $null,
        [switch]$OmitCsrf
    )

    $headers = @{}
    if (-not $OmitCsrf) {
        if ([string]::IsNullOrWhiteSpace([string]$Context.Csrf)) {
            [void](Set-Csrf -Context $Context)
        }
        $headers['X-XSRF-TOKEN'] = [string]$Context.Csrf
    }
    return Invoke-Api -Context $Context -Method $Method -Path $Path -Body $Body -Headers $headers
}

function Get-Cookie {
    param([object]$Context, [string]$Name, [string]$Path = '/api/v1/auth/refresh')
    $cookies = $Context.Jar.GetCookies([Uri]($script:BaseUrl + $Path))
    return $cookies[$Name]
}

function Assert-NoAuthTokensInJson {
    param([string]$Id, [string]$Name, [object]$Response)

    $names = @()
    if ($null -ne $Response.Body -and $null -ne $Response.Body.data) {
        $names = @($Response.Body.data.PSObject.Properties.Name)
    }

    $hasToken = ($names -contains 'accessToken') -or ($names -contains 'refreshToken')
    return Test-Assert $Id $Name (-not $hasToken) 'response contains no accessToken/refreshToken fields' 'authentication token field leaked in JSON response'
}

function Write-TestReport {
    $root = Split-Path -Parent $PSScriptRoot
    $outDir = Join-Path $root 'test-results'
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $csvPath = Join-Path $outDir "api-test-$stamp.csv"
    $mdPath = Join-Path $outDir "api-test-$stamp.md"

    $script:Results | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8
    $pass = @($script:Results | Where-Object { $_.Status -eq 'PASS' }).Count
    $fail = @($script:Results | Where-Object { $_.Status -eq 'FAIL' }).Count
    $skip = @($script:Results | Where-Object { $_.Status -eq 'SKIP' -or $_.Status -eq 'BLOCKED' }).Count

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('# Chatbot API Test Run')
    $lines.Add('')
    $lines.Add("- **Run time:** $($script:StartedAt.ToString('yyyy-MM-dd HH:mm:ss'))")
    $lines.Add("- **Base URL:** $script:BaseUrl")
    $lines.Add('- **Environment:** local test environment; do not run against production data')
    $lines.Add("- **Summary:** $pass PASS, $fail FAIL, $skip SKIP/BLOCKED, $($script:Results.Count) total")
    $lines.Add('')
    $lines.Add('| ID | Status | Test | Details |')
    $lines.Add('|---|---|---|---|')

    foreach ($item in $script:Results) {
        $name = ([string]$item.Test).Replace('|', '\|')
        $detail = ([string]$item.Details).Replace('|', '\|')
        $lines.Add("| $($item.Id) | $($item.Status) | $name | $detail |")
    }

    $lines.Add('')
    $lines.Add('No passwords, JWTs, refresh-token values, CSRF-token values, or message bodies are intentionally written to this report.')
    Set-Content -Path $mdPath -Value $lines -Encoding UTF8

    Write-Host ''
    Write-Host ("SUMMARY: {0} PASS / {1} FAIL / {2} SKIP or BLOCKED / {3} total" -f $pass, $fail, $skip, $script:Results.Count)
    Write-Host "Markdown report: $mdPath"
    Write-Host "CSV report:      $csvPath"

    if ($fail -gt 0) { $script:ExitCode = 1 }
}

$script:Contexts = New-Object System.Collections.Generic.List[object]
$script:ExitCode = 0

try {
    Write-Host "Chatbot API integration tests: $script:BaseUrl"
    Write-Host 'Use a dedicated test database. A fresh app process is recommended before each full run because rate limits are in memory.'
    Write-Host ''

    $anon = New-ClientContext; $script:Contexts.Add($anon) | Out-Null
    $homeResponse = Invoke-Api -Context $anon -Method 'GET' -Path '/'
    [void](Test-Assert 'WEB-01' 'Static app entry point' ($homeResponse.Status -eq 200) 'GET / returned HTTP 200' "GET / returned HTTP $($homeResponse.Status)")

    $csrfResponse = Set-Csrf -Context $anon
    [void](Test-Assert 'CSRF-01' 'CSRF bootstrap endpoint' ($csrfResponse.Status -eq 200 -and -not [string]::IsNullOrWhiteSpace([string]$anon.Csrf)) 'HTTP 200 and a non-empty CSRF token were returned' "unexpected response (HTTP $($csrfResponse.Status)")

    $csrfCookies = $anon.Jar.GetCookies([Uri]($script:BaseUrl + '/api/v1/csrf'))
    $csrfCookie = $csrfCookies['XSRF-TOKEN']
    [void](Test-Assert 'CSRF-02' 'CSRF cookie materialized' ($null -ne $csrfCookie -and $csrfCookie.HttpOnly) 'XSRF-TOKEN cookie present and HttpOnly; CSRF remains distinct from auth credentials' 'XSRF-TOKEN cookie was not set or was not HttpOnly')

    $unauthChats = Invoke-Api -Context $anon -Method 'GET' -Path '/api/v1/chats'
    [void](Test-Assert 'AUTH-01' 'Protected route blocks anonymous client' ($unauthChats.Status -eq 401) 'GET /api/v1/chats returned HTTP 401' "expected 401, received HTTP $($unauthChats.Status)")

    $csrfDenied = Invoke-Unsafe -Context $anon -Method 'POST' -Path '/api/v1/auth/register' -Body @{ email = "no-csrf-$script:RunId@example.com"; password = 'StrongTestPassword#2026' } -OmitCsrf
    [void](Test-Assert 'CSRF-03' 'Unsafe request without CSRF header is rejected' ($csrfDenied.Status -eq 403) 'POST without X-XSRF-TOKEN returned HTTP 403' "expected 403, received HTTP $($csrfDenied.Status)")

    $a = New-ClientContext; $script:Contexts.Add($a) | Out-Null
    [void](Set-Csrf -Context $a)
    $emailA = "qa-a-$script:RunId@example.com"
    $passwordA = 'StrongTestPassword#2026'
    $registrationA = Invoke-Unsafe -Context $a -Method 'POST' -Path '/api/v1/auth/register' -Body @{ email = $emailA.ToUpperInvariant(); password = $passwordA }
    [void](Test-Assert 'REG-01' 'Register first test account and normalize email' ($registrationA.Status -eq 201 -and $registrationA.Body.data.email -eq $emailA) 'HTTP 201; returned email is normalized to lowercase' "expected 201 + normalized email, received HTTP $($registrationA.Status)")

    if ($registrationA.Status -ne 201) { throw 'Cannot continue: first test account registration failed.' }

    $weak = Invoke-Unsafe -Context $a -Method 'POST' -Path '/api/v1/auth/register' -Body @{ email = "weak-$script:RunId@example.com"; password = 'WeakPass1' }
    [void](Test-Assert 'REG-02' 'Reject password shorter than 12 characters' ($weak.Status -eq 400) 'weak password rejected with HTTP 400' "expected 400, received $($weak.Status)")

    $duplicate = Invoke-Unsafe -Context $a -Method 'POST' -Path '/api/v1/auth/register' -Body @{ email = $emailA; password = $passwordA }
    [void](Test-Assert 'REG-03' 'Reject duplicate email' ($duplicate.Status -eq 409) 'duplicate account rejected with HTTP 409' "expected 409, received $($duplicate.Status)")

    $tooLongPassword = 'A' * 73
    $tooLong = Invoke-Unsafe -Context $a -Method 'POST' -Path '/api/v1/auth/register' -Body @{ email = "long-$script:RunId@example.com"; password = $tooLongPassword }
    [void](Test-Assert 'REG-04' 'Reject password over 72 characters/bytes' ($tooLong.Status -eq 400) 'oversized password rejected with HTTP 400' "expected 400, received $($tooLong.Status)")

    $b = New-ClientContext; $script:Contexts.Add($b) | Out-Null
    [void](Set-Csrf -Context $b)
    $emailB = "qa-b-$script:RunId@example.com"
    $passwordB = 'OtherStrongPassword#2026'
    $registrationB = Invoke-Unsafe -Context $b -Method 'POST' -Path '/api/v1/auth/register' -Body @{ email = $emailB; password = $passwordB }
    [void](Test-Assert 'REG-05' 'Register second test account' ($registrationB.Status -eq 201) 'second test account created with HTTP 201' "expected 201, received $($registrationB.Status)")

    if ($registrationB.Status -ne 201) { throw 'Cannot continue: second test account registration failed.' }

    # Bean-validation failures occur before the controller method and do not consume this controller-level limit.
    $fifthRegistration = Invoke-Unsafe -Context $b -Method 'POST' -Path '/api/v1/auth/register' -Body @{ email = "rate-reg-1-$script:RunId@example.com"; password = $passwordB }
    [void](Test-Assert 'RATE-01' 'Fifth controller-level registration attempt is allowed' ($fifthRegistration.Status -eq 201) 'fifth counted registration attempt returned HTTP 201' "expected 201, received $($fifthRegistration.Status)")

    $sixthRegistration = Invoke-Unsafe -Context $b -Method 'POST' -Path '/api/v1/auth/register' -Body @{ email = "rate-reg-2-$script:RunId@example.com"; password = $passwordB }
    [void](Test-Assert 'RATE-03' 'Sixth controller-level registration attempt is limited' ($sixthRegistration.Status -eq 429) 'sixth counted registration attempt rejected with HTTP 429' "expected 429, received $($sixthRegistration.Status)")

    $wrongLogin = Invoke-Unsafe -Context $a -Method 'POST' -Path '/api/v1/auth/login' -Body @{ email = $emailA; password = 'WrongTestPassword#2026' }
    [void](Test-Assert 'AUTH-02' 'Reject incorrect password' ($wrongLogin.Status -eq 401) 'incorrect credentials rejected with HTTP 401' "expected 401, received $($wrongLogin.Status)")

    [void](Set-Csrf -Context $a)
    $loginA = Invoke-Unsafe -Context $a -Method 'POST' -Path '/api/v1/auth/login' -Body @{ email = $emailA; password = $passwordA }
    [void](Test-Assert 'AUTH-03' 'Successful login' ($loginA.Status -eq 200 -and $loginA.Body.success -eq $true) 'login returned HTTP 200 and success=true' "expected successful login, received HTTP $($loginA.Status)")

    if ($loginA.Status -ne 200) { throw 'Cannot continue: first test account login failed.' }

    [void](Assert-NoAuthTokensInJson 'AUTH-04' 'Login does not return auth tokens in JSON' $loginA)

    $aAccess = Get-Cookie -Context $a -Name 'ACCESS_TOKEN' -Path '/api/v1/me'
    $aRefresh = Get-Cookie -Context $a -Name 'REFRESH_TOKEN' -Path '/api/v1/auth/refresh'
    $cookiesOk = ($null -ne $aAccess) -and ($null -ne $aRefresh) -and $aAccess.HttpOnly -and $aRefresh.HttpOnly -and ($aAccess.Path -eq '/api/v1') -and ($aRefresh.Path -eq '/api/v1/auth') -and (-not $aAccess.Secure) -and (-not $aRefresh.Secure)
    [void](Test-Assert 'COOKIE-01' 'Auth cookies are HttpOnly with scoped paths' $cookiesOk 'ACCESS_TOKEN and REFRESH_TOKEN are HttpOnly with expected paths; Secure=false for local HTTP' 'missing cookie, incorrect HttpOnly/path attribute, or Secure flag unexpected for local HTTP')

    $meA = Invoke-Api -Context $a -Method 'GET' -Path '/api/v1/me'
    [void](Test-Assert 'AUTH-05' 'Access cookie authenticates /me' ($meA.Status -eq 200 -and $meA.Body.data.email -eq $emailA) 'GET /api/v1/me returned the logged-in identity' "expected HTTP 200 and user identity, received HTTP $($meA.Status)")

    $bearerOnly = New-ClientContext; $script:Contexts.Add($bearerOnly) | Out-Null
    $accessForHarnessOnly = (Get-Cookie -Context $a -Name 'ACCESS_TOKEN' -Path '/api/v1/me').Value
    $bearerAttempt = Invoke-Api -Context $bearerOnly -Method 'GET' -Path '/api/v1/me' -Headers @{ Authorization = "Bearer $accessForHarnessOnly" }
    [void](Test-Assert 'AUTH-09' 'Bearer header alone does not authenticate requests' ($bearerAttempt.Status -eq 401) 'cookie-only authentication contract enforced with HTTP 401' "expected 401, received HTTP $($bearerAttempt.Status)")

    $invalid = New-ClientContext; $script:Contexts.Add($invalid) | Out-Null
    [void](Set-Csrf -Context $invalid)
    $badCookie = [System.Net.Cookie]::new('ACCESS_TOKEN', 'not-a-valid-jwt', '/api/v1', ([Uri]$script:BaseUrl).Host)
    $badCookie.HttpOnly = $true
    $invalid.Jar.Add([Uri]($script:BaseUrl + '/api/v1/me'), $badCookie)
    $badJwtMe = Invoke-Api -Context $invalid -Method 'GET' -Path '/api/v1/me'
    [void](Test-Assert 'AUTH-06' 'Invalid access JWT is rejected' ($badJwtMe.Status -eq 401) 'invalid ACCESS_TOKEN produced HTTP 401' "expected 401, received HTTP $($badJwtMe.Status)")

    [void](Set-Csrf -Context $a)
    $csrfOnChat = Invoke-Unsafe -Context $a -Method 'POST' -Path '/api/v1/chats' -OmitCsrf

    [void](Test-Assert 'CSRF-04' 'Authenticated mutation still requires CSRF' ($csrfOnChat.Status -eq 403) 'POST /chats without CSRF header returned HTTP 403' "expected 403, received HTTP $($csrfOnChat.Status)")

    $badCsrf = Invoke-Api -Context $a -Method 'POST' -Path '/api/v1/chats' -Headers @{ 'X-XSRF-TOKEN' = 'not-a-real-csrf-token' }
    [void](Test-Assert 'CSRF-05' 'Incorrect CSRF token is rejected' ($badCsrf.Status -eq 403) 'invalid X-XSRF-TOKEN returned HTTP 403' "expected 403, received HTTP $($badCsrf.Status)")

    $createdChat = Invoke-Unsafe -Context $a -Method 'POST' -Path '/api/v1/chats'
    
    [void](Test-Assert 'CHAT-01' 'Create chat' ($createdChat.Status -eq 201 -and -not [string]::IsNullOrWhiteSpace([string]$createdChat.Body.data.id)) 'chat created with HTTP 201 and UUID' "expected 201 + UUID, received HTTP $($createdChat.Status)")
    if ($createdChat.Status -ne 201) { throw 'Cannot continue: chat creation failed.' }
    $chatId = [string]$createdChat.Body.data.id
    
    $chatList = Invoke-Api -Context $a -Method 'GET' -Path '/api/v1/chats'
    $foundChat = @($chatList.Body.data | Where-Object { $_.id -eq $chatId }).Count -gt 0
    [void](Test-Assert 'CHAT-02' 'New chat appears in history' ($chatList.Status -eq 200 -and $foundChat) 'created chat appears in GET /chats history' "chat not found in history; HTTP $($chatList.Status)")
    $initialMessages = Invoke-Api -Context $a -Method 'GET' -Path "/api/v1/chats/$chatId/messages"
    [void](Test-Assert 'CHAT-03' 'New chat starts with empty message history' ($initialMessages.Status -eq 200 -and @($initialMessages.Body.data).Count -eq 0) 'message history is empty before first send' "expected empty history, received HTTP $($initialMessages.Status)")

    # Diagnostic: compare the CSRF header token with the cookie token.
    $csrfCookies = $a.Jar.GetCookies(
        [Uri]($script:BaseUrl + '/api/v1/chats')
    )
    $csrfCookie = $csrfCookies['XSRF-TOKEN']

    $csrfCookiesBeforePost = $a.Jar.GetCookies(
        [Uri]($script:BaseUrl + "/api/v1/chats/$chatId/messages")
    )
    $csrfCookieBeforePost = $csrfCookiesBeforePost['XSRF-TOKEN']

    $blankMessage = Invoke-Unsafe -Context $a -Method 'POST' -Path "/api/v1/chats/$chatId/messages" -Body @{ content = '   ' }
    [void](Test-Assert 'CHAT-04' 'Reject blank chat message' ($blankMessage.Status -eq 400) 'blank message rejected with HTTP 400' "expected 400, received $($blankMessage.Status)")

    $longMessage = 'x' * 8001
    $oversizedMessage = Invoke-Unsafe -Context $a -Method 'POST' -Path "/api/v1/chats/$chatId/messages" -Body @{ content = $longMessage }
    [void](Test-Assert 'CHAT-05' 'Reject message longer than 8000 characters' ($oversizedMessage.Status -eq 400) 'oversized message rejected with HTTP 400' "expected 400, received $($oversizedMessage.Status)")

    $send = Invoke-Unsafe -Context $a -Method 'POST' -Path "/api/v1/chats/$chatId/messages" -Body @{ content = 'QA integration test message' }
    [void](Test-Assert 'CHAT-06' 'Send message through local AI stub' ($send.Status -eq 200 -and [string]$send.Body.data.content -like 'TEST_STUB_REPLY:*') 'chat endpoint returned deterministic stub reply' "expected HTTP 200 + TEST_STUB_REPLY; received HTTP $($send.Status). Ensure the local AI stub is running and its secret matches.")

    $messageHistory = Invoke-Api -Context $a -Method 'GET' -Path "/api/v1/chats/$chatId/messages"
    $historyCount = @($messageHistory.Body.data).Count
    [void](Test-Assert 'CHAT-07' 'Persist user and assistant messages' ($messageHistory.Status -eq 200 -and $historyCount -eq 2) 'history contains exactly one user and one assistant message' "expected 2 messages, found $historyCount; HTTP $($messageHistory.Status)")

    $badUuid = Invoke-Api -Context $a -Method 'GET' -Path '/api/v1/chats/not-a-uuid/messages'
    [void](Test-Assert 'INPUT-01' 'Malformed chat UUID produces client error, not server error' ($badUuid.Status -ge 400 -and $badUuid.Status -lt 500) 'malformed UUID returned a 4xx response' "expected 4xx, received HTTP $($badUuid.Status); this may be mapped to 500 by the generic exception handler")

    [void](Set-Csrf -Context $b)
    $loginB = Invoke-Unsafe -Context $b -Method 'POST' -Path '/api/v1/auth/login' -Body @{ email = $emailB; password = $passwordB }
    [void](Test-Assert 'AUTH-07' 'Second account can log in independently' ($loginB.Status -eq 200) 'second account login returned HTTP 200' "expected 200, received $($loginB.Status)")
    if ($loginB.Status -ne 200) { throw 'Cannot continue: second test account login failed.' }
    [void](Assert-NoAuthTokensInJson 'AUTH-08' 'Second login does not return auth tokens in JSON' $loginB)

    $bChats = Invoke-Api -Context $b -Method 'GET' -Path '/api/v1/chats'
    [void](Test-Assert 'AUTHZ-01' 'Chat history is isolated by owner' ($bChats.Status -eq 200 -and @($bChats.Body.data).Count -eq 0) 'second user sees no first-user chats' "second user could see unexpected chat(s); HTTP $($bChats.Status)")
    $crossRead = Invoke-Api -Context $b -Method 'GET' -Path "/api/v1/chats/$chatId/messages"
    [void](Test-Assert 'AUTHZ-02' 'Other account cannot read chat messages' ($crossRead.Status -eq 404) 'cross-user message read returned HTTP 404' "expected 404, received HTTP $($crossRead.Status)")
    $crossSend = Invoke-Unsafe -Context $b -Method 'POST' -Path "/api/v1/chats/$chatId/messages" -Body @{ content = 'cross-user attempt' }
    [void](Test-Assert 'AUTHZ-03' 'Other account cannot send to someone else chat' ($crossSend.Status -eq 404) 'cross-user send returned HTTP 404' "expected 404, received HTTP $($crossSend.Status)")

    # Refresh account A: retain the previous refresh value only in memory for replay testing.
    $aOldRefresh = (Get-Cookie -Context $a -Name 'REFRESH_TOKEN' -Path '/api/v1/auth/refresh').Value
    [void](Set-Csrf -Context $a)
    
    $refreshA = Invoke-Unsafe -Context $a -Method 'POST' -Path '/api/v1/auth/refresh'
    [void](Test-Assert 'REFRESH-01' 'Refresh rotates cookies successfully' ($refreshA.Status -eq 200) "refresh returned HTTP $($refreshA.Status)" 'expected HTTP 200')
    if ($refreshA.Status -eq 200) {
        [void](Assert-NoAuthTokensInJson 'REFRESH-02' 'Refresh does not return auth tokens in JSON' $refreshA)
        $aNewRefresh = (Get-Cookie -Context $a -Name 'REFRESH_TOKEN' -Path '/api/v1/auth/refresh').Value
        [void](Test-Assert 'REFRESH-03' 'Refresh token value changes on rotation' ($aNewRefresh -and $aNewRefresh -ne $aOldRefresh) 'refresh cookie rotated to a different value' 'refresh cookie missing or unchanged')
        $meAfterRefresh = Invoke-Api -Context $a -Method 'GET' -Path '/api/v1/me'
        [void](Test-Assert 'REFRESH-04' 'Access remains usable after refresh' ($meAfterRefresh.Status -eq 200) 'GET /me returned HTTP 200 after refresh' "expected 200, received HTTP $($meAfterRefresh.Status)")
    }

    # Refresh account B once, then replay the old token from a second client.
    $bOldRefresh = (Get-Cookie -Context $b -Name 'REFRESH_TOKEN' -Path '/api/v1/auth/refresh').Value
    [void](Set-Csrf -Context $b)
    $refreshB = Invoke-Unsafe -Context $b -Method 'POST' -Path '/api/v1/auth/refresh'
    [void](Test-Assert 'REFRESH-05' 'Second account refreshes normally before replay test' ($refreshB.Status -eq 200) "refresh returned HTTP $($refreshB.Status)" 'expected HTTP 200')
    if ($refreshB.Status -eq 200 -and -not [string]::IsNullOrWhiteSpace([string]$bOldRefresh)) {
        $replay = New-ClientContext; $script:Contexts.Add($replay) | Out-Null
        [void](Set-Csrf -Context $replay)
        $refreshCookie = [System.Net.Cookie]::new('REFRESH_TOKEN', $bOldRefresh, '/api/v1/auth', ([Uri]$script:BaseUrl).Host)
        $refreshCookie.HttpOnly = $true
        $replay.Jar.Add([Uri]($script:BaseUrl + '/api/v1/auth/refresh'), $refreshCookie)
        $replayResponse = Invoke-Unsafe -Context $replay -Method 'POST' -Path '/api/v1/auth/refresh'
        [void](Test-Assert 'REFRESH-06' 'Replayed rotated token is rejected' ($replayResponse.Status -eq 401) 'reuse of prior refresh token returned HTTP 401' "expected 401, received HTTP $($replayResponse.Status)")
        [void](Set-Csrf -Context $b)
        $afterReplay = Invoke-Unsafe -Context $b -Method 'POST' -Path '/api/v1/auth/refresh'
        [void](Test-Assert 'REFRESH-07' 'Replay revokes the complete refresh-token family' ($afterReplay.Status -eq 401) 'newer token from same family also rejected after replay' "expected family-wide rejection (401), received HTTP $($afterReplay.Status)")
    } else {
        Add-Result 'REFRESH-06' 'Replayed rotated token is rejected' 'BLOCKED' 'initial second-account refresh failed; replay test could not run'
        Add-Result 'REFRESH-07' 'Replay revokes the complete refresh-token family' 'BLOCKED' 'initial second-account refresh failed; replay test could not run'
    }

    # Logout account A and verify that the server also rejects its previous live refresh token.
    $aLiveRefreshBeforeLogout = Get-Cookie -Context $a -Name 'REFRESH_TOKEN' -Path '/api/v1/auth/refresh'
    [void](Set-Csrf -Context $a)
    $logoutA = Invoke-Unsafe -Context $a -Method 'POST' -Path '/api/v1/auth/logout'
    [void](Test-Assert 'LOGOUT-01' 'Logout responds with 204 and clears cookies' ($logoutA.Status -eq 204) "logout returned HTTP $($logoutA.Status)" 'expected HTTP 204')
    $accessAfterLogout = Get-Cookie -Context $a -Name 'ACCESS_TOKEN' -Path '/api/v1/me'
    $refreshAfterLogout = Get-Cookie -Context $a -Name 'REFRESH_TOKEN' -Path '/api/v1/auth/refresh'
    [void](Test-Assert 'LOGOUT-02' 'Logout removes auth cookies from client jar' (($null -eq $accessAfterLogout -or [string]::IsNullOrEmpty([string]$accessAfterLogout.Value)) -and ($null -eq $refreshAfterLogout -or [string]::IsNullOrEmpty([string]$refreshAfterLogout.Value))) 'access and refresh cookies removed after logout' 'one or more auth cookies remain in the client jar')

    if ($null -ne $aLiveRefreshBeforeLogout -and -not [string]::IsNullOrWhiteSpace($aLiveRefreshBeforeLogout.Value)) {
        $logoutReplay = New-ClientContext; $script:Contexts.Add($logoutReplay) | Out-Null
        [void](Set-Csrf -Context $logoutReplay)
        $oldLogoutCookie = [System.Net.Cookie]::new('REFRESH_TOKEN', $aLiveRefreshBeforeLogout.Value, '/api/v1/auth', ([Uri]$script:BaseUrl).Host)
        $oldLogoutCookie.HttpOnly = $true
        $logoutReplay.Jar.Add([Uri]($script:BaseUrl + '/api/v1/auth/refresh'), $oldLogoutCookie)
        $afterLogoutRefresh = Invoke-Unsafe -Context $logoutReplay -Method 'POST' -Path '/api/v1/auth/refresh'
        [void](Test-Assert 'LOGOUT-03' 'Refresh token from logged-out family is rejected' ($afterLogoutRefresh.Status -eq 401) 'refresh after logout returned HTTP 401' "expected 401, received HTTP $($afterLogoutRefresh.Status)")
    } else {
        Add-Result 'LOGOUT-03' 'Refresh token from logged-out family is rejected' 'BLOCKED' 'could not capture the active refresh cookie before logout'
    }

    $logoutEmpty = New-ClientContext; $script:Contexts.Add($logoutEmpty) | Out-Null
    [void](Set-Csrf -Context $logoutEmpty)
    $logoutWithoutCookie = Invoke-Unsafe -Context $logoutEmpty -Method 'POST' -Path '/api/v1/auth/logout'
    [void](Test-Assert 'LOGOUT-04' 'Logout without a refresh cookie remains idempotent' ($logoutWithoutCookie.Status -eq 204) 'logout without refresh cookie returned HTTP 204' "expected 204, received HTTP $($logoutWithoutCookie.Status)")

    $invalidRefresh = New-ClientContext; $script:Contexts.Add($invalidRefresh) | Out-Null
    [void](Set-Csrf -Context $invalidRefresh)
    $randomRefreshCookie = [System.Net.Cookie]::new('REFRESH_TOKEN', 'invalid-refresh-value', '/api/v1/auth', ([Uri]$script:BaseUrl).Host)
    $randomRefreshCookie.HttpOnly = $true
    $invalidRefresh.Jar.Add([Uri]($script:BaseUrl + '/api/v1/auth/refresh'), $randomRefreshCookie)
    $invalidRefreshResponse = Invoke-Unsafe -Context $invalidRefresh -Method 'POST' -Path '/api/v1/auth/refresh'
    [void](Test-Assert 'REFRESH-08' 'Unknown refresh token is rejected' ($invalidRefreshResponse.Status -eq 401) 'unknown refresh token returned HTTP 401' "expected 401, received HTTP $($invalidRefreshResponse.Status)")

    $missingRefresh = New-ClientContext; $script:Contexts.Add($missingRefresh) | Out-Null
    [void](Set-Csrf -Context $missingRefresh)
    $missingRefreshResponse = Invoke-Unsafe -Context $missingRefresh -Method 'POST' -Path '/api/v1/auth/refresh'
    [void](Test-Assert 'REFRESH-09' 'Missing refresh cookie is rejected' ($missingRefreshResponse.Status -eq 401) 'refresh without a cookie returned HTTP 401' "expected 401, received HTTP $($missingRefresh.Status)")

    if ($SkipSlowLoginRateLimitCheck) {
        Add-Result 'RATE-02' 'Login rate limit rejects the 11th attempt' 'SKIP' 'SkipSlowLoginRateLimitCheck was specified'
    } else {
        $rateClient = New-ClientContext; $script:Contexts.Add($rateClient) | Out-Null
        [void](Set-Csrf -Context $rateClient)
        $rateEmail = "login-rate-$script:RunId@example.com"
        $attemptStatuses = New-Object System.Collections.Generic.List[int]

        for ($i = 1; $i -le 11; $i++) {
            $attempt = Invoke-Unsafe -Context $rateClient -Method 'POST' -Path '/api/v1/auth/login' -Body @{ email = $rateEmail; password = 'WrongTestPassword#2026' }
            $attemptStatuses.Add([int]$attempt.Status) | Out-Null
        }

        $firstTenUnauthorized = (@($attemptStatuses | Select-Object -First 10 | Where-Object { $_ -ne 401 }).Count -eq 0)
        $eleventhLimited = ($attemptStatuses[10] -eq 429)
        [void](Test-Assert 'RATE-02' 'Login rate limit rejects the 11th attempt' ($firstTenUnauthorized -and $eleventhLimited) 'first ten attempts returned 401 and the 11th returned 429' ("expected ten 401 responses then 429; statuses: " + ($attemptStatuses -join ',')))
    }
}
catch {
    Add-Result 'RUN-SETUP' 'Test run reached an unexpected blocker' 'BLOCKED' ("$($_.Exception.GetType().Name): " + $_.Exception.Message)
    $script:ExitCode = 1
}
finally {
    foreach ($ctx in $script:Contexts) {
        try { $ctx.Client.Dispose() } catch {}
        try { $ctx.Handler.Dispose() } catch {}
    }
    Write-TestReport
}
exit $script:ExitCode
