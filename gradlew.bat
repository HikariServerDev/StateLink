@echo off
if defined STATELINK_PYTHON (set "STATELINK_PYTHON_CMD=%STATELINK_PYTHON%") else (set "STATELINK_PYTHON_CMD=python")
"%STATELINK_PYTHON_CMD%" "%~dp0tools\gradle_runner.py" %*
exit /b %ERRORLEVEL%
