@echo off
title 音乐机器人 - 一键启动
cd /d "%~dp0"

echo ==========================================
echo    音乐机器人 一键启动
echo ==========================================
echo.

where javac >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 javac，请先安装 JDK 并配置 PATH 环境变量。
    echo        可先打开 cmd 执行 javac -version 验证。
    pause
    exit /b 1
)

echo [1/2] 正在编译 music.java ...
javac -encoding UTF-8 music.java
if errorlevel 1 (
    echo.
    echo [错误] 编译失败，请查看上方错误信息。
    pause
    exit /b 1
)

echo [2/2] 编译成功，正在启动...
echo 提示：请勿关闭本窗口，日志会显示在这里；关闭窗口将同时退出程序。
echo.
java music

echo.
echo 程序已退出。
pause