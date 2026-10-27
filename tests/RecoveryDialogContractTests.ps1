# Source-contract regression checks, not Android rendering/input instrumentation.
# The packaged build compiles the real widgets against Android API 36. Physical
# DPAD traversal and Shield theme rendering remain a separate hardware gate.
param([string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
$source = Get-Content "$ProjectRoot\app\src\main\java\com\shieldtools\launchermanager\MainActivity.kt" -Raw
$dialog = ($source -split 'private fun requestNativeSwitch\(')[1] -split 'private fun prepareNativeSwitch\('
$body = $dialog[0]
$passed = 0
function Assert-Contract([bool]$condition, [string]$name) {
    if (!$condition) { throw "FAIL: $name" }
    $script:passed++; Write-Output "PASS $name"
}
Assert-Contract ($body -match 'CheckBox\(this\)' -and $body -match '\.setView\(acknowledged\)' -and $body -notmatch 'setMultiChoiceItems|setItems|setSingleChoiceItems') 'Acknowledgement is custom checkbox content, never a message-hidden list'
Assert-Contract ($body.Contains('I can restore NVIDIA Stock if recovery is needed')) 'Recovery acknowledgement is labelled independently of fallback consent'
Assert-Contract ($body -match 'val acknowledged = CheckBox' -and $body -match 'isChecked = false') 'Each attempt creates a fresh unchecked acknowledgement'
Assert-Contract ($body -match 'isFocusable = true' -and $body -match 'acknowledged.requestFocus\(\)') 'Remote starts on the native focusable CheckBox'
Assert-Contract ($body -match 'minHeight = dp\(64\)' -and $body -match 'textSize = 18f') 'Acknowledgement retains a readable TV-sized control'
Assert-Contract ($body -match 'review.isEnabled = false' -and $body -match 'setOnCheckedChangeListener.*updateReview\(checked\)' -and $body -match 'review.isEnabled = checked') 'Review cannot be focused/activated until checked and disables again when unchecked'
Assert-Contract ($body -match 'nextFocusDownId = if \(checked\) review.id else cancel.id' -and $body -match 'nextFocusForwardId = acknowledged.nextFocusDownId') 'DPAD Down/forward reaches Review when checked and Cancel when unchecked'
Assert-Contract ($body -match 'review.nextFocusUpId = acknowledged.id' -and $body -match 'cancel.nextFocusUpId = acknowledged.id') 'DPAD Up from either action returns to checkbox'
Assert-Contract ($body -match 'if \(acknowledged.isChecked\) prepareNativeSwitch\(mode,component\)') 'Review callback independently rechecks acknowledgement'
Assert-Contract ($body -match 'setNegativeButton\("Cancel",null\)' -and $body -notmatch 'NativeSwitchSession\.|\.confirm\(|\.setEnabled\(') 'Cancel/Back cannot perform any switching operation in readiness dialog'
Assert-Contract ($source -notmatch 'override fun onKey|setOnKeyListener') 'Native CheckBox DPAD_CENTER/Enter handling is not intercepted'
Assert-Contract ($source -match 'setNegativeButton\("Cancel"\).*NativeSwitchSession.cancel\(ticket\)' -and $source -match 'setOnCancelListener.*NativeSwitchSession.cancel\(ticket\)') 'Final review Cancel and Back cancel the one-use transaction ticket'
$setup = (($source -split 'private fun requestActivation\(')[1] -split 'private fun requestNativeSwitch\(')[0]
$preview = (($source -split 'private fun confirmPreview\(')[1] -split 'private fun chooseActivity\(')[0]
$cards = (($source -split 'private fun card\(')[1] -split 'private fun confirmPreview\(')[0]
Assert-Contract ($cards.Contains('else requestActivation(entry)')) 'New launcher activation enters setup review before switching consent'
Assert-Contract ($setup.Contains('isChecked = false') -and $setup.Contains('review.isEnabled = false') -and $setup.Contains('review.isEnabled = checked') -and $setup.Contains('if (setupComplete.isChecked) requestNativeSwitch("SELECT", entry.component)')) 'Setup completion requires a fresh physical acknowledgement before normal activation review'
Assert-Contract ($setup.Contains('setNeutralButton("Preview / finish setup")') -and $setup.Contains('confirmPreview(entry)') -and $setup -notmatch 'NativeSwitchSession\.|\.confirm\(|\.prepare\(|\.setEnabled\(') 'Setup preview cannot create a ticket, transaction or package mutation'
Assert-Contract ($preview.Contains('repository.preview(entry)') -and $preview.Contains('No activation countdown or switch transaction is running') -and $preview -notmatch 'NativeSwitchSession\.|\.setPreferred\(|\.setEnabled\(|grantRuntimePermission') 'Onboarding preview uses ordinary launch without deadlines, HOME writes or automatic permissions'
Assert-Contract ($setup.Contains('setupComplete.requestFocus()') -and $setup.Contains('setupComplete.nextFocusDownId = if (checked) review.id else preview.id') -and $setup.Contains('preview.nextFocusUpId = setupComplete.id')) 'Remote navigation reaches setup preview and acknowledged review'
Write-Output "$passed recovery dialog source-contract tests passed"
