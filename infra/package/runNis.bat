@echo off
pushd "%~dp0nis" || exit /b 1
java -Xms4G -Xmx6G -cp ".;./*;../libs/*" org.nem.deploy.CommonStarter
set "exitCode=%ERRORLEVEL%"
popd
exit /b %exitCode%
