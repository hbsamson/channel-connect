param(
	[switch]$UseOpenAI,
	[switch]$NoEnv,
	[ValidateNotNullOrEmpty()]
	[string]$Profiles = 'local,demo'
)

$ErrorActionPreference = 'Stop'
$repoDirectory = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoDirectory
if (-not $NoEnv -and (Test-Path -LiteralPath '.env')) {
	foreach ($entry in Get-Content -LiteralPath '.env') {
		if ($entry -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
			$settingName = $matches[1]
			$settingValue = $matches[2].Trim()
			if (($settingValue.StartsWith('"') -and $settingValue.EndsWith('"')) -or ($settingValue.StartsWith("'") -and $settingValue.EndsWith("'"))) {
				$settingValue = $settingValue.Substring(1, $settingValue.Length - 2)
			}
			if (-not [string]::IsNullOrWhiteSpace($settingValue)) {
				[Environment]::SetEnvironmentVariable($settingName, $settingValue, 'Process')
			}
		}
	}
}
$env:AI_PROVIDER = if ($UseOpenAI) { 'openai' } else { 'mock' }
Write-Host "Starting dealership demo; profiles: $Profiles; AI provider: $env:AI_PROVIDER. SMS provider depends on the selected profiles and configuration; Telegram is simulated."
& .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=$Profiles" '-Dspring-boot.run.arguments=--debug=false'
if ($LASTEXITCODE -ne 0) { throw 'Demo application startup failed.' }
