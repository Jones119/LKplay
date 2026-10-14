#!/system/bin/sh
# CS11 microphone release — run this when CarPlay cannot capture the car microphone.
#
# Why: this head unit's own voice assistant (com.baidu.che.codriver and the ecarx voice services)
# keeps an always-on 16 kHz capture open, and this 4.4 audio HAL answers a competing AudioRecord with
# ERROR_INVALID_OPERATION (-3) on the first read, so CarPlay's uplink gets nothing. The assistant
# restarts itself within seconds, so freeing the microphone means keeping it stopped.
#
# Usage (rootd queue, the same channel as cs11_wireless_tune.sh):
#   adb -s 0123456789ABCDEF push tools/cs11_release_mic.sh /data/.android/rootcmd.sh
#   adb -s 0123456789ABCDEF shell "cat /data/.android/rootout.txt"
# It loops for 3 minutes by default; press Siri / Amap voice inside that window and watch the app log
# for "microphone probe source=... result=N" (a non-negative result means the microphone opened).
#
# Prefer the vendor path when it exists: disabling the wake word in the head unit's own settings is
# cleaner and permanent, and this script is the fallback when that setting is missing.

DURATION_SECONDS="${1:-180}"
PACKAGES="com.baidu.che.codriver com.baidu.che.codriver:remote ecarx.voiceservice ecarx.voiceservice.impl ecarx.voice.service"

echo "=== CS11 microphone release for ${DURATION_SECONDS}s ==="
END=$(( $(date +%s) + DURATION_SECONDS ))
ROUNDS=0
while [ "$(date +%s)" -lt "$END" ]; do
  for pkg in $PACKAGES; do
    am force-stop "$pkg" 2>/dev/null
  done
  ROUNDS=$((ROUNDS + 1))
  sleep 2
done

echo "rounds=$ROUNDS"
echo "--- remaining voice processes (any line here means it respawned) ---"
busybox ps | busybox grep -E "[c]odriver|[v]oiceservice" | busybox head -5
echo "=== CS11_MIC_RELEASE_DONE ==="
