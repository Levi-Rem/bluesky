@echo off
rem 飞行数据准备与分析工作台 —— 一键打包并运行（Windows）
rem 用法：run.bat            （打包 + 启动，访问 http://127.0.0.1:8090）
rem       run.bat --skip-build   （直接启动已构建的 jar）
setlocal
cd /d "%~dp0"

if not "%1"=="--skip-build" (
  echo ==^> 打包后端（跳过测试）
  call mvn -q -DskipTests package
  if errorlevel 1 goto :fail
)

if not exist "target\data-prep-workbench.jar" (
  echo 未找到 target\data-prep-workbench.jar，请先执行完整构建 & goto :fail
)

echo ==^> 启动：http://127.0.0.1:8090  （H2 文件库 .\data\，Ctrl+C 停止）
java -jar "target\data-prep-workbench.jar"
goto :eof

:fail
exit /b 1
