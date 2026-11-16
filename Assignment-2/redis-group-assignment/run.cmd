@echo off
rem QuickBite on Redis (Windows) -- needs Java 21+ and a running Redis (docker compose up -d)
rem   run.cmd                    start the API server on http://localhost:8080
rem   run.cmd check [A|B|C|D|E]  run the automatic checks
rem   run.cmd worker w1          start a kitchen worker (add --crash to simulate a crash)
cd /d "%~dp0"
if exist out rmdir /s /q out
javac -Xlint:none -nowarn -d out src\*.java checks\*.java
if errorlevel 1 exit /b 1

if "%1"=="check" (
  java -cp out Check %2
) else if "%1"=="worker" (
  java -cp out KitchenWorker %2 %3
) else (
  java -cp out App
)
