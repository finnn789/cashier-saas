"""CPU and system resources monitoring tool."""

import asyncio
import ctypes
import os
import platform
import sys
from typing import Any

from app.tools.base import BaseTool


def _get_windows_cpu_usage() -> float:
    """Read CPU percentage on Windows using GetSystemTimes without external libraries."""
    class FILETIME(ctypes.Structure):
        _fields_ = [("dwLowDateTime", ctypes.c_uint), ("dwHighDateTime", ctypes.c_uint)]

    def to_int(ft: FILETIME) -> int:
        return (ft.dwHighDateTime << 32) + ft.dwLowDateTime

    idle = FILETIME()
    kernel = FILETIME()
    user = FILETIME()

    get_times = ctypes.windll.kernel32.GetSystemTimes
    if not get_times(ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user)):
        return 0.0

    i1, k1, u1 = to_int(idle), to_int(kernel), to_int(user)
    # Very short pause for sampling
    import time
    time.sleep(0.15)

    if not get_times(ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user)):
        return 0.0

    i2, k2, u2 = to_int(idle), to_int(kernel), to_int(user)

    usr = u2 - u1
    ker = k2 - k1
    idl = i2 - i1
    sys_time = usr + ker

    if sys_time == 0:
        return 0.0

    usage = (sys_time - idl) * 100.0 / sys_time
    return max(0.0, min(100.0, round(usage, 1)))


def _get_windows_ram_usage() -> dict[str, Any]:
    """Read RAM status on Windows using GlobalMemoryStatusEx."""
    class MEMORYSTATUSEX(ctypes.Structure):
        _fields_ = [
            ("dwLength", ctypes.c_ulong),
            ("dwMemoryLoad", ctypes.c_ulong),
            ("ullTotalPhys", ctypes.c_ulonglong),
            ("ullAvailPhys", ctypes.c_ulonglong),
            ("ullTotalPageFile", ctypes.c_ulonglong),
            ("ullAvailPageFile", ctypes.c_ulonglong),
            ("ullTotalVirtual", ctypes.c_ulonglong),
            ("ullAvailVirtual", ctypes.c_ulonglong),
            ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
        ]

    stat = MEMORYSTATUSEX()
    stat.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
    if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(stat)):
        total_gb = round(stat.ullTotalPhys / (1024**3), 2)
        free_gb = round(stat.ullAvailPhys / (1024**3), 2)
        used_gb = round(total_gb - free_gb, 2)
        return {
            "ram_usage_percent": int(stat.dwMemoryLoad),
            "ram_total_gb": total_gb,
            "ram_used_gb": used_gb,
            "ram_free_gb": free_gb,
        }
    return {}


class CpuUsageTool(BaseTool):
    """Tool to inspect current CPU and RAM usage of the host laptop/machine."""

    @property
    def name(self) -> str:
        return "check_laptop_cpu_usage"

    @property
    def description(self) -> str:
        return (
            "Mengecek persentase penggunaan CPU (CPU usage), beban memori RAM, "
            "dan kondisi performa laptop/server saat ini secara langsung."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {},
            "required": [],
        }

    async def execute(self, *, tenant_id: str, **kwargs: Any) -> Any:
        # Run CPU sampling in thread to avoid blocking the event loop
        loop = asyncio.get_running_loop()

        if sys.platform == "win32":
            cpu_usage = await loop.run_in_executor(None, _get_windows_cpu_usage)
            ram_info = _get_windows_ram_usage()
        else:
            # Fallback for unix/linux
            try:
                load1, _, _ = os.getloadavg()
                cpu_usage = round(load1 * 10, 1)
            except (AttributeError, OSError):
                cpu_usage = 0.0
            ram_info = {}

        status = "normal"
        if cpu_usage > 85.0:
            status = "sangat tinggi (high load)"
        elif cpu_usage > 65.0:
            status = "sedang aktif bekerja"

        result = {
            "status": status,
            "cpu_usage_percent": cpu_usage,
            "os": f"{platform.system()} {platform.release()}",
            **ram_info,
        }
        return result
