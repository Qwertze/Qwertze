@echo off
rem Starts the Stash Manager server from a copy of the built jar, so rebuilding the project can never
rem replace the jar under a running server (Java loads classes from the jar lazily; a replaced jar breaks it).
rem Data (config, API key, indexes, orders, icon cache) stays in stash-manager-data next to where this is run.
setlocal
set "HERE=%~dp0"
set "JAR=%HERE%build\libs\stash-manager-server.jar"
set "RUNDIR=%CD%\stash-manager-data"
if not exist "%JAR%" (
  echo Build it first: gradlew.bat -p stash-manager-server build
  exit /b 1
)
if not exist "%RUNDIR%" mkdir "%RUNDIR%"
copy /y "%JAR%" "%RUNDIR%\server-running.jar" >nul
if errorlevel 1 (
  echo Couldn't copy the jar. Is the server already running?
  exit /b 1
)
java -jar "%RUNDIR%\server-running.jar" %*
