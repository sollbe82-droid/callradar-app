@echo off
C:\AndroidSdk\platform-tools\adb.exe devices -l > C:\CallRadar\_test\devices.log 2>&1
C:\AndroidSdk\platform-tools\adb.exe shell getprop ro.product.model >> C:\CallRadar\_test\devices.log 2>&1
