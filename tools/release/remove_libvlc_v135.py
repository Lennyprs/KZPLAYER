#!/usr/bin/env python3
"""V135: delete only the retired KZ LibVLC files after extracting the complete source archive."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
if 'versionName "3.7.4"' not in (ROOT/'app/build.gradle').read_text():
    raise SystemExit('Extraire d abord les sources 3.7.4 dans le depot KZPLAYER.')
FILES=['app/src/main/assets/libvlc-LGPL-2.1.txt', 'app/src/main/assets/libvlc-NOTICE.txt', 'app/src/main/java/com/kzplayer/app/UnifiedVodController.java', 'app/src/main/java/com/kzplayer/app/VlcReleaseQueue.kt', 'app/src/main/java/com/kzplayer/app/VlcStreamBridge.java', 'app/src/main/java/com/kzplayer/app/VlcVodActivity.kt', 'app/src/main/java/com/kzplayer/app/VodFrameHealth.java', 'app/src/main/java/com/kzplayer/app/VodPlaybackPlan.java', 'app/src/main/java/com/kzplayer/app/VodRenderersFactory.java', 'app/src/main/java/com/kzplayer/app/VodVideoPolicy.java', 'app/src/test/java/com/kzplayer/app/V362DecoderPolicyTest.java', 'app/src/test/java/com/kzplayer/app/VlcFallbackTest.java', 'app/src/test/java/com/kzplayer/app/VlcPrimaryTest.java', 'app/src/test/java/com/kzplayer/app/VodFrameHealthTest.java']
for name in FILES:
    path=ROOT/name
    if path.is_symlink() or ROOT not in path.resolve().parents:
        raise SystemExit('Chemin inattendu : '+name)
    if path.is_file():
        path.unlink()
        print('Retire : '+name)
print('Nettoyage VLC termine. FFmpeg audio Media3 conserve.')
