
@echo off
setlocal enabledelayedexpansion

REM ============================================
REM ByteGraph Runner (Windows .bat)
REM Usage: run-bytegraph.bat "C:\path\to\MyClass.class"
REM  - arg1 (required): absolute path to the .class file to analyze
REM ============================================

REM 0) Check argument
if "%~1"=="" (
  echo [ERROR] .class file path is required.
  echo Usage: %~nx0 "C:\path\to\MyClass.class"
  exit /b 1
)
set "CLASS_PATH=%~1"

REM 3) Run Gradle (must be executed in project root)
echo [INFO] Running Gradle...
gradle run --args="%CLASS_PATH%"
set "RC=%ERRORLEVEL%"

if not "%RC%"=="0" (
  echo [ERROR] Gradle run failed (exit code=%RC%)
  exit /b %RC%
)

echo [INFO] Done. Check the out/ folder for the generated JSON.
exit /b 0
