@echo off
rem S7 provision launcher, args passthrough, e.g. s7_provision.bat --ssid xxx --pass yyy
"C:\Users\luoyu\AppData\Local\Python\bin\python.exe" "%~dp0s7_provision.py" %*
