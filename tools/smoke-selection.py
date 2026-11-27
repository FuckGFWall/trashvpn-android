"""LG/ADB selection check. Use a test device with both WARP identities already registered."""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET

MAIN_ACTIVITY = "com.leo.trashvpn.MainActivity"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB device serial (required if multiple devices)")
    parser.add_argument("--package", choices=["com.leo.trashvpn", "com.leo.trashvpn.debug"], default="com.leo.trashvpn")
    parser.add_argument("--mode", choices=["fresh", "cache"], default="fresh")
    parser.add_argument("--protocol", choices=["AWG", "WG", "MASQUE"], action="append")
    parser.add_argument("--intensity", choices=["low", "medium", "high"], help="set scan intensity; otherwise keep current setting")
    parser.add_argument("--require-cache-hit", action="store_true", help="fail if cache mode has to start a fresh scan")
    parser.add_argument("--scan-only", action="store_true", help="check fresh selection without connecting a system VPN")
    parser.add_argument("--home", action="store_true", help="press Home after starting a scan-only run")
    parser.add_argument("--theme", choices=["dark", "light", "system"], help="exercise the theme selector before testing")
    parser.add_argument("--restart-after-wave", action="store_true", help="force-stop only this test app after a saved wave, then check its resume cursor")
    args = parser.parse_args()
    PKG = args.package
    activity = PKG + "/" + MAIN_ACTIVITY
    if args.home and not args.scan_only:
        parser.error("--home requires --scan-only")
    if args.restart_after_wave and (not args.scan_only or len(args.protocol or []) != 1):
        parser.error("--restart-after-wave requires --scan-only and a single --protocol")
    if args.require_cache_hit and args.mode != "cache":
        parser.error("--require-cache-hit needs --mode cache")
    if args.scan_only and args.mode != "fresh":
        parser.error("--scan-only is always a fresh scan")
    prefix = ["adb"] + (["-s", args.serial] if args.serial else [])

    def adb(*argv):
        return subprocess.check_output(prefix + list(argv), timeout=20).decode("utf-8", "replace")

    def view():
        for attempt in range(3):
            raw = adb("exec-out", "uiautomator", "dump", "/dev/tty")
            if "<?xml" in raw and "</hierarchy>" in raw:
                return list(ET.fromstring(raw[raw.index("<?xml"):raw.index("</hierarchy>")+12]).iter("node"))
            time.sleep(.5)
        raise RuntimeError("Android did not return the current UI hierarchy")

    def tap(value, attr="text"):
        if isinstance(value,str):
            value=[value]
        nodes = [n for n in view() if n.get(attr) in value and n.get("enabled") == "true" and n.get("package") == PKG]
        if not nodes:
            raise RuntimeError(f"App control unavailable: {value}. Unlock the device, grant VPN consent and provision credentials first.")
        x1,y1,x2,y2 = map(int,re.findall(r"\d+", nodes[-1].get("bounds")))
        adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2))
        time.sleep(.4)

    if re.search(r"NetworkAgentInfo.*?type: VPN", adb("shell","dumpsys","connectivity")):
        raise RuntimeError("Disconnect the active VPN before this test.")
    adb("shell","am","start","-W","-n",activity)
    pid = adb("shell","pidof",PKG).strip()
    assert pid.isdigit()

    def log():
        return adb("logcat","-d","--pid="+pid,"-s","TrashVPN").splitlines()

    def wait(before, marks, seconds):
        until = time.monotonic()+seconds
        while time.monotonic()<until:
            if args.scan_only and re.search(r"NetworkAgentInfo.*?type: VPN", adb("shell","dumpsys","connectivity")):
                raise RuntimeError("System VPN observed during scan-only test")
            for line in log():
                if line not in before:
                    observed.setdefault(line,None)
                if line not in before and any(mark in line for mark in marks):
                    print(line, flush=True)
                    return line
            time.sleep(.5)
        raise RuntimeError("Timed out waiting for "+"/".join(marks))

    protocols=args.protocol or ["AWG","WG","MASQUE"]
    restart_cursor=None
    for p in (protocols*2 if args.restart_after_wave else protocols):
        tap("设置","content-desc")
        if args.theme:
            tap("主题","content-desc")
            tap({"dark":"深色","light":"浅色","system":"跟随系统"}[args.theme])
        tap("隧道协议","content-desc")
        tap({"AWG":"AWG","WG":"WireGuard","MASQUE":"MASQUE"}[p])
        if args.intensity:
            tap({"low":"低","medium":"中","high":"高"}[args.intensity])
        # Keep scans independent of the user's automatic-connection setting during this check.
        auto_id=PKG+":id/settings_auto_connect"
        auto=next((n for n in view() if n.get("resource-id")==auto_id),None)
        if auto is None:
            width,height=map(int,re.findall(r"(\d+)x(\d+)",adb("shell","wm","size"))[-1])
            adb("shell","input","swipe",str(width//2),str(height*4//5),str(width//2),str(height//3),"350")
            auto=next((n for n in view() if n.get("resource-id")==auto_id),None)
        if auto is None or auto.get("checked")!="false":
            raise RuntimeError("Turn off 优选完成后自动连接 before running this check.")
        tap("返回主页","content-desc")
        tap("自动选择","content-desc")
        observed={}
        before=set(log())
        tap(["优选","继续优选","重新优选"] if args.mode=="fresh" else "连接 VPN",
            "text" if args.mode=="fresh" else "content-desc")
        if args.home:
            adb("shell","input","keyevent","KEYCODE_HOME")
        if args.restart_after_wave and restart_cursor is None:
            wave=wait(before,["COARSE "+p+" "],90)
            restart_cursor=int(re.search(r"cursor=(\d+)",wave).group(1))
            adb("shell","am","force-stop",PKG)
            adb("shell","am","start","-W","-n",activity)
            pid=adb("shell","pidof",PKG).strip()
            continue
        try:
            if args.mode=="fresh":
                selected=wait(before,["SELECTED "+p+" ","FAIL "+p+" "],360)
                if "SELECTED " not in selected:
                    raise RuntimeError(p+" did not verify a WARP exit")
                if not args.scan_only:
                    tap("连接 VPN","content-desc")
            marker="SELECTED " if args.scan_only else "PASS "
            line=selected if args.scan_only else wait(before,["PASS "+p+" ","FAIL "+p+" "],360)
            run_log = list(observed)
            if args.scan_only and any("TUN_CONNECT " in s for s in run_log):
                raise RuntimeError("TUN setup attempted during scan")
            if not args.scan_only and args.mode=="fresh" and "PASS " in line:
                stages = [s for s in run_log if "SELECTED "+p+" " in s or "TUN_CONNECT "+p+" " in s]
                if len(stages) < 2 or "SELECTED " not in stages[0] or "TUN_CONNECT " not in stages[1]:
                    raise RuntimeError("VPN did not wait for selection to finish")
            scans = [s for s in run_log if "SCAN "+p+" " in s]
            if restart_cursor is not None and not any(int(re.search(r"cursor=(\d+)",s).group(1)) >= restart_cursor for s in scans):
                raise RuntimeError("Scan did not resume the persisted cursor")
            for sample in run_log:
                if "SAMPLE " in sample or "SCAN " in sample or "COARSE " in sample:
                    print(sample)
            if marker not in line:
                raise RuntimeError(p+" did not verify a WARP exit")
            if args.mode == "fresh" and not scans:
                raise RuntimeError(p+" did not run a fresh scan")
            if args.mode == "fresh" and args.intensity and not any("SCAN "+p+" "+args.intensity.upper()+" " in s for s in scans):
                raise RuntimeError("Unexpected scan intensity")
            if args.require_cache_hit and scans:
                raise RuntimeError(p+" did not use the cache")
        finally:
            if args.home:
                adb("shell","am","start","-W","-n",activity)
            if any(n.get("package")==PKG and n.get("content-desc")=="断开 VPN" and n.get("enabled")=="true" for n in view()):
                before_stop=set(log())
                tap("断开 VPN","content-desc")
                wait(before_stop,["STOPPED"],35)


if __name__ == "__main__":
    main()
