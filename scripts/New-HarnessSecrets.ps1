#Requires -Version 5.1
<#
.SYNOPSIS
Generate independent Harness secrets in the current PowerShell process.
.DESCRIPTION
Each selected secret is 32 cryptographically random bytes encoded as Base64.
Defaults to JWT and model secrets. Mail secrets can be selected with -Names.
Run once during initial setup: rerunning replaces the selected process values.
No secrets are printed, written to application.yml, or persisted to User/Machine environment variables.
#>
[CmdletBinding()]
param(
    [ValidateSet('HARNESS_JWT_SECRET', 'HARNESS_MODEL_SECRET_KEY', 'HARNESS_MAIL_HMAC_KEY', 'HARNESS_MAIL_ENCRYPTION_KEY')]
    [string[]]$Names = @('HARNESS_JWT_SECRET', 'HARNESS_MODEL_SECRET_KEY')
)

function New-HarnessSecret {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
        [Convert]::ToBase64String($bytes)
    }
    finally {
        $rng.Dispose()
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

foreach ($keyName in $Names) {
    [Environment]::SetEnvironmentVariable($keyName, (New-HarnessSecret), 'Process')
}

Write-Host ('Generated secrets in this PowerShell process: ' + ($Names -join ', '))
