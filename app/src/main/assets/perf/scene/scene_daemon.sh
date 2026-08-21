#!/system/bin/sh
# HanFeng PerfTuner - SCENE standalone power daemon (universal SoC)
# 自动检测 CPU 拓扑与厂商，按档位应用调度/频率参数。
# 参数通过环境变量传入，可由 App 持久化后注入。

SCENE_LOG="${SCENE_LOG:-/data/adb/HanFengPerf/scene.log}"
SCENE_PROFILE="${SCENE_PROFILE:-performance}"
SCENE_FMAX_CAP="${SCENE_FMAX_CAP:-auto}"
SCENE_ADAPTIVE="${SCENE_ADAPTIVE:-0}"
SCENE_THERMAL="${SCENE_THERMAL:-49500}"
SCENE_DISABLE_MIGT="${SCENE_DISABLE_MIGT:-1}"
SCENE_HISPD_LOAD="${SCENE_HISPD_LOAD:-90}"

log_msg() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') [$1] $2" >> "$SCENE_LOG" 2>/dev/null
}

set_value() {
  value=$1
  path=$2
  if [ -f "$path" ]; then
    current_value="$(cat "$path" 2>/dev/null)"
    if [ "$current_value" != "$value" ]; then
      chmod 0664 "$path" 2>/dev/null
      echo "$value" > "$path" 2>/dev/null
    fi
  fi
}

lock_value() {
  if [ -f "$2" ]; then
    chmod 0777 "$2" 2>/dev/null
    echo "$1" > "$2" 2>/dev/null
    chmod 0444 "$2" 2>/dev/null
  fi
}

build_topology() {
  NUM_POLICY=0
  for policy in /sys/devices/system/cpu/cpufreq/policy*; do
    [ -d "$policy" ] || continue
    [ -f "$policy/cpuinfo_max_freq" ] || continue
    related=$(cat "$policy/related_cpus" 2>/dev/null)
    maxfreq=$(cat "$policy/cpuinfo_max_freq" 2>/dev/null)
    [ -z "$related" ] && continue
    policy_no=${policy##*policy}
    eval "POL_RANGE_${NUM_POLICY}=\"${related}\""
    eval "POL_MAX_${NUM_POLICY}=\"${maxfreq}\""
    eval "POL_NO_${NUM_POLICY}=\"${policy_no}\""
    NUM_POLICY=$((NUM_POLICY + 1))
  done

  PRESENT=$(tr ', ' '\n\n' < /sys/devices/system/cpu/present 2>/dev/null | tr -d '\n')
  PRESENT=${PRESENT:-0-7}
  MAX_CORE=$(echo "$PRESENT" | tr ',' '\n' | sed 's/.*-//' | sort -n | tail -1)
  MIN_CORE=$(echo "$PRESENT" | tr ',' '\n' | sed 's/-.*//' | sort -n | head -1)
  MAX_CORE=${MAX_CORE:-7}
  MIN_CORE=${MIN_CORE:-0}
}

detect_vendor() {
  PLATFORM=$(getprop ro.board.platform 2>/dev/null)
  case "$PLATFORM" in
    mt*|MT*) VENDOR=MTK ;;
    *) VENDOR=QCOM ;;
  esac
  log_msg "I" "platform=$PLATFORM vendor=$VENDOR profile=$SCENE_PROFILE topo=$PRESENT($MIN_CORE-$MAX_CORE) policies=$NUM_POLICY"
}

apply_common_sched() {
  if [ -d /proc/sys/walt ]; then
    lock_value 0 /proc/sys/walt/sched_sbt_enable 2>/dev/null
    lock_value 90 /proc/sys/walt/sched_pipeline_util_thres 2>/dev/null
    lock_value 85 /proc/sys/walt/walt_rtg_cfs_boost_prio 2>/dev/null
  fi
  if [ "$SCENE_FMAX_CAP" != "auto" ] && [ -f /proc/sys/walt/sched_fmax_cap ]; then
    set_value "$SCENE_FMAX_CAP" /proc/sys/walt/sched_fmax_cap 2>/dev/null
  fi
  for fi in /sys/devices/system/cpu/cpufreq/policy*/walt/hispeed_load; do
    [ -f "$fi" ] && lock_value "$SCENE_HISPD_LOAD" "$fi"
  done
  if [ "$SCENE_ADAPTIVE" = "0" ]; then
    for d in /sys/devices/system/cpu/cpufreq/policy*/walt; do
      [ -f "$d/adaptive_high_freq" ] && lock_value 0 "$d/adaptive_high_freq"
      [ -f "$d/adaptive_low_freq" ] && lock_value 0 "$d/adaptive_low_freq"
    done
  fi
}

apply_thermal() {
  t_message=/sys/class/thermal/thermal_message
  if [ -f "$t_message/cpu_limits" ]; then
    chmod 664 "$t_message/cpu_limits" 2>/dev/null
    i=$MIN_CORE
    while [ "$i" -le "$MAX_CORE" ]; do
      maxfreq=$(cat "/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq" 2>/dev/null)
      [ -n "$maxfreq" ] && echo "cpu$i $maxfreq" > "$t_message/cpu_limits" 2>/dev/null
      i=$((i + 1))
    done
    chmod 444 "$t_message/cpu_limits" 2>/dev/null
  fi
  [ -f "$t_message/cpu_nolimit_temp" ] && set_value "$SCENE_THERMAL" "$t_message/cpu_nolimit_temp"
}

apply_qcom_common() {
  MIGT=/sys/module/migt/parameters
  if [ -d "$MIGT" ]; then
    echo 1 > "$MIGT/force_reset_runtime" 2>/dev/null
    if [ "$SCENE_DISABLE_MIGT" = "1" ]; then
      lock_value 1 "$MIGT/glk_disable" 2>/dev/null
      lock_value 0 "$MIGT/glk_fbreak_enable" 2>/dev/null
      lock_value 0 "$MIGT/force_cluster_sched_enable" 2>/dev/null
      lock_value -1 "$MIGT/render_prefer_cluster" 2>/dev/null
      lock_value -1 "$MIGT/vip_prefer_cluster" 2>/dev/null
      lock_value -1 "$MIGT/stask_prefer_cluster" 2>/dev/null
      echo '0:0 1:0 2:0 3:0 4:0 5:0 6:0 7:0' > "$MIGT/migt_ceiling_freq" 2>/dev/null
    fi
  fi
  if [ -d /sys/module/metis ]; then
    for i in /sys/module/metis/parameters/*reset*; do
      [ -f "$i" ] && echo 1 > "$i" 2>/dev/null
    done
    lock_value 0 /sys/module/metis/parameters/thermal_break_enable 2>/dev/null
    lock_value 0 /sys/module/metis/parameters/is_break_enable 2>/dev/null
    lock_value 0 /sys/module/metis/parameters/mi_freq_enable 2>/dev/null
    for file in /sys/module/metis/parameters/add*; do
      [ -f "$file" ] && chmod 444 "$file" 2>/dev/null
    done
  fi
}

apply_mtk_common() {
  if [ -d /proc/game_opt ]; then
    lock_value 1 /proc/game_opt/disable_cpufreq_limit 2>/dev/null
    lock_value 1 /sys/module/cpufreq_bouncing/parameters/enable 2>/dev/null
    lock_value 0 /sys/devices/platform/soc/soc:oplus-omrg/oplus-omrg0/ruler_enable 2>/dev/null
  fi
  if [ -d /sys/module/mtk_fpsgo ]; then
    lock_value 0 /sys/module/mtk_fpsgo/parameters/cfp_onoff 2>/dev/null
  fi
}

apply_cpuset_unlock() {
  for cpuset in /dev/cpuset/top-app /dev/cpuset/foreground /dev/cpuset/game /dev/cpuset/rt; do
    if [ -f "$cpuset/cpus" ]; then
      chmod 0664 "$cpuset/cpus" 2>/dev/null
      set_value "$PRESENT" "$cpuset/cpus" 2>/dev/null
      chmod 0444 "$cpuset/cpus" 2>/dev/null
    fi
  done
}

apply_core_ctl() {
  for core in /sys/devices/system/cpu/cpu*/core_ctl/max_cpus; do
    [ -f "$core" ] && { chmod 0444 "$core" 2>/dev/null; echo 18 > "$core" 2>/dev/null; }
  done
  for core in /sys/devices/system/cpu/cpu*/core_ctl/min_cpus; do
    [ -f "$core" ] && { chmod 0444 "$core" 2>/dev/null; echo 0 > "$core" 2>/dev/null; }
  done
  # 天玑/部分高通用 need_cpus
  for core in /sys/devices/system/cpu/cpu*/core_ctl/need_cpus; do
    [ -f "$core" ] && { chmod 0444 "$core" 2>/dev/null; echo 18 > "$core" 2>/dev/null; }
  done
}

# ---- 主流程 ----
build_topology
detect_vendor

apply_common_sched
apply_thermal
case "$VENDOR" in
  MTK) apply_mtk_common ;;
  *) apply_qcom_common ;;
esac
apply_cpuset_unlock
apply_core_ctl

log_msg "I" "scene standalone applied (profile=$SCENE_PROFILE)"
exit 0