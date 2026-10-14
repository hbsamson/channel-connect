param(
	[ValidateSet('All','Booking','Switching','History','Reschedule','Inquiries','Privacy')][string]$Scenario = 'All',
	[switch]$Reset,
	[switch]$NoEnv,
	[string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'
$settings = @{}
$envPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
if (-not $NoEnv -and (Test-Path -LiteralPath $envPath)) {
	foreach ($entry in Get-Content -LiteralPath $envPath) {
		if ($entry -match '^\s*(ADMIN_USERNAME|ADMIN_PASSWORD)\s*=\s*(.*)$') {
			$settingName = $matches[1]
			$settingValue = $matches[2].Trim().Trim('"').Trim("'")
			if ($settingValue) { $settings[$settingName] = $settingValue }
		}
	}
}
$demoUser = if ($env:ADMIN_USERNAME) { $env:ADMIN_USERNAME } elseif ($settings.ContainsKey('ADMIN_USERNAME')) { $settings['ADMIN_USERNAME'] } else { 'demo' }
$demoPassword = if ($env:ADMIN_PASSWORD) { $env:ADMIN_PASSWORD } elseif ($settings.ContainsKey('ADMIN_PASSWORD')) { $settings['ADMIN_PASSWORD'] } else { 'demo-local-only' }
$headers = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($demoUser + ':' + $demoPassword)) }

function Invoke-DemoApi([string]$Route, $Body = $null, [string]$Method = 'POST') {
	$arguments = @{ Uri = $BaseUrl.TrimEnd('/') + '/api/admin/demo/' + $Route; Method = $Method; Headers = $headers; ContentType = 'application/json' }
	if ($null -ne $Body) { $arguments.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 30)) }
	$response = Invoke-WebRequest @arguments -UseBasicParsing
	$reader = New-Object System.IO.StreamReader($response.RawContentStream, [Text.Encoding]::UTF8)
	try { $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
}
function Start-Campaign([string]$Customer) {
	$execution = Invoke-DemoApi 'campaigns/start' @{ customerId = $Customer }
	Write-Host "Campaign $Customer started: $($execution.status) on $($execution.currentChannel)"
	return $execution
}
function Reply([string]$Customer, [string]$Channel, [string]$Text) {
	$result = Invoke-DemoApi 'replies' @{ channel = $Channel; accountId = 'demo'; senderId = $Customer + '-' + $Channel.ToLowerInvariant(); externalId = [Guid]::NewGuid().ToString(); content = $Text; timestamp = [DateTimeOffset]::UtcNow.ToString('o') }
	Write-Host "[$Channel SIMULATED] Customer: $Text"
	Write-Host "Assistant: $($result.reply) [$($result.deliveryStatus)]"
}
if ($Reset) { $null = Invoke-DemoApi 'reset' } else { $null = Invoke-DemoApi 'seed' }
if ($Scenario -in @('All','Booking')) {
	$null = Start-Campaign 'demo-booking'
	Reply 'demo-booking' 'SMS' 'I want to book registration on 2026-10-16'
	Reply 'demo-booking' 'SMS' '9am'
}
if ($Scenario -in @('All','Switching')) {
	$execution = Start-Campaign 'demo-no-response'
	1..3 | ForEach-Object { $null = Invoke-DemoApi 'campaigns/advance' @{ executionId = $execution.id; force = $true } }
	$view = Invoke-DemoApi 'customers/demo-no-response' -Method 'GET'
	$view.attempts | Select-Object channel, attemptNumber, scriptKey, status | Format-Table
	$view.messages | Select-Object channel, content, deliveryStatus | Format-Table -Wrap
	Reply 'demo-no-response' 'TELEGRAM' 'Book 2026-10-17 10am'
	$null = Invoke-DemoApi 'campaigns/advance' @{ executionId = $execution.id; force = $true }
}
if ($Scenario -in @('All','History')) {
	$null = Start-Campaign 'demo-cross-channel'
	Reply 'demo-cross-channel' 'SMS' 'Book 2026-10-16'
	Reply 'demo-cross-channel' 'TELEGRAM' 'Actually 2026-10-17 instead'
	Reply 'demo-cross-channel' 'TELEGRAM' '2pm'
	$view = Invoke-DemoApi 'customers/demo-cross-channel' -Method 'GET'
	$view.messages | Select-Object channel, direction, content | Format-Table -Wrap
}
if ($Scenario -in @('All','Reschedule')) {
	Reply 'demo-reschedule' 'SMS' 'Book 2026-10-16 10am'
	Reply 'demo-reschedule' 'TELEGRAM' 'Move to 2026-10-17 9am'
	Reply 'demo-reschedule' 'TELEGRAM' 'Cancel my appointment'
	Reply 'demo-reschedule' 'TELEGRAM' 'Yes'
}
if ($Scenario -in @('All','Inquiries')) {
	Reply 'demo-renewal' 'SMS' 'How much is registration?'
	Reply 'demo-renewal' 'SMS' 'What are your hours?'
	Reply 'demo-renewal' 'SMS' 'What is included in 3-in-1?'
	Reply 'demo-renewal' 'SMS' 'Write a Python program'
}
if ($Scenario -in @('All','Privacy')) {
	$execution = Start-Campaign 'demo-privacy'
	Reply 'demo-privacy' 'SMS' 'Why did you contact me?'
	Reply 'demo-privacy' 'TELEGRAM' 'STOP'
	$null = Invoke-DemoApi 'campaigns/advance' @{ executionId = $execution.id; force = $true }
	$view = Invoke-DemoApi 'customers/demo-privacy' -Method 'GET'
	Write-Host "Global opt-out recorded: $($view.optedOut)"
}
Write-Host 'Inspect /api/admin/demo/customers/{id} for combined messages, workflow, appointment, and events.'
