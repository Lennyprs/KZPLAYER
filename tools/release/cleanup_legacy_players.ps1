$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$gradle = Join-Path $root 'app/build.gradle'
if (!(Test-Path -LiteralPath $gradle) -or !(Select-String -LiteralPath $gradle -SimpleMatch 'versionName "3.8.0"' -Quiet)) { throw 'Extraire les sources 3.8.0 avant le nettoyage.' }
$files = @(
    'app/src/main/assets/libvlc-LGPL-2.1.txt'
    'app/src/main/assets/libvlc-NOTICE.txt'
    'app/src/main/java/com/kzplayer/app/UnifiedVodController.java'
    'app/src/main/java/com/kzplayer/app/VlcReleaseQueue.kt'
    'app/src/main/java/com/kzplayer/app/VlcStreamBridge.java'
    'app/src/main/java/com/kzplayer/app/VlcVodActivity.kt'
    'app/src/main/java/com/kzplayer/app/VodFrameHealth.java'
    'app/src/main/java/com/kzplayer/app/VodPlaybackPlan.java'
    'app/src/main/java/com/kzplayer/app/VodRenderersFactory.java'
    'app/src/main/java/com/kzplayer/app/VodVideoPolicy.java'
    'app/src/test/java/com/kzplayer/app/V362DecoderPolicyTest.java'
    'app/src/test/java/com/kzplayer/app/VlcFallbackTest.java'
    'app/src/test/java/com/kzplayer/app/VlcPrimaryTest.java'
    'app/src/test/java/com/kzplayer/app/VodFrameHealthTest.java'
    'app/src/main/java/com/kzplayer/app/VodAudioRenderersFactory.java'
    'app/src/main/java/com/kzplayer/app/VodCompatibilityRenderersFactory.java'
    'app/src/main/java/com/kzplayer/app/VodStereoMatrix.java'
    'app/src/main/java/com/kzplayer/app/DolbyBaseConfiguration.java'
    'app/src/main/java/com/kzplayer/app/Media3VodPolicy.java'
    'app/src/main/java/com/kzplayer/app/VodSourceHints.java'
    'app/src/test/java/com/kzplayer/app/VodStereoMatrixTest.java'
    'app/src/test/java/com/kzplayer/app/DolbyBaseConfigurationTest.java'
    'app/src/test/java/com/kzplayer/app/Media3VodPolicyTest.java'
    'app/src/test/java/com/kzplayer/app/VodSourceHintsTest.java'
    'app/src/androidTest/java/com/kzplayer/app/VodSourceHintsAndroidTest.java'
)
# Validate all paths before deleting any file. Refuse links/junctions below the repo.
foreach ($name in $files) {
    $path = Join-Path $root $name
    $current = $path
    while ($current -and $current -ne $root) {
        if (Test-Path -LiteralPath $current) {
            $entry = Get-Item -LiteralPath $current -Force
            if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Lien inattendu : $name" }
        }
        $current = Split-Path $current -Parent
    }
}
foreach ($name in $files) {
    $path = Join-Path $root $name
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        Remove-Item -LiteralPath $path -Force
        Write-Host "Retire : $name"
    }
}
Write-Host 'Nettoyage termine. Lecteurs actuels conserves.' -ForegroundColor Green
