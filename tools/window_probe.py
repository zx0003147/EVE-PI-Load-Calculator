"""Window probe for portable-release smoke tests.

Finds visible top-level windows that belong to a given process image name
and prints title plus size. Exit codes:

    0  at least one window wider than --min-width was found (main GUI up)
    1  the process is not running at all
    2  process runs but only small dialogs are visible (likely an error box)
    3  no visible windows

Usage:
    python window_probe.py [--name "EVE PI Load Calculator.exe"] [--min-width 1000]
"""
import argparse
import ctypes
import ctypes.wintypes as wt
import sys

kernel32 = ctypes.windll.kernel32
user32 = ctypes.windll.user32


class PROCESSENTRY32W(ctypes.Structure):
    _fields_ = [("dwSize", wt.DWORD),
                ("cntUsage", wt.DWORD),
                ("th32ProcessID", wt.DWORD),
                ("th32DefaultHeapID", ctypes.POINTER(ctypes.c_ulong)),
                ("th32ModuleID", wt.DWORD),
                ("cntThreads", wt.DWORD),
                ("th32ParentProcessID", wt.DWORD),
                ("pcPriClassBase", wt.LONG),
                ("dwFlags", wt.DWORD),
                ("szExeFile", wt.WCHAR * 260)]


def process_pids(image_name):
    snap = kernel32.CreateToolhelp32Snapshot(2, 0)  # TH32CS_SNAPPROCESS
    entry = PROCESSENTRY32W()
    entry.dwSize = ctypes.sizeof(PROCESSENTRY32W)
    pids = set()
    if kernel32.Process32FirstW(snap, ctypes.byref(entry)):
        while True:
            if entry.szExeFile == image_name:
                pids.add(entry.th32ProcessID)
            if not kernel32.Process32NextW(snap, ctypes.byref(entry)):
                break
    kernel32.CloseHandle(snap)
    return pids


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default="EVE PI Load Calculator.exe")
    ap.add_argument("--min-width", type=int, default=1000)
    args = ap.parse_args()

    pids = process_pids(args.name)
    print("pids:", pids or "NONE")
    if not pids:
        print("RESULT: FAIL (process not running)")
        return 1

    enum_proc = ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
    found = []

    def on_window(hwnd, lparam):
        pid = wt.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if pid.value in pids and user32.IsWindowVisible(hwnd):
            buf = ctypes.create_unicode_buffer(256)
            user32.GetWindowTextW(hwnd, buf, 256)
            rect = wt.RECT()
            user32.GetWindowRect(hwnd, ctypes.byref(rect))
            found.append((buf.value, rect.right - rect.left, rect.bottom - rect.top))
        return True

    user32.EnumWindows(enum_proc(on_window), 0)

    if not found:
        print("RESULT: FAIL (no visible windows)")
        return 3
    for title, w, h in found:
        print(f"window: '{title}' {w}x{h}")
    main_windows = [f for f in found if f[1] >= args.min_width]
    if main_windows:
        print("RESULT: PASS (main window visible)")
        return 0
    print("RESULT: FAIL (only small dialog visible)")
    return 2


if __name__ == "__main__":
    sys.exit(main())
