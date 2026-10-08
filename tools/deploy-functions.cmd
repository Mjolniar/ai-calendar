@echo off
rem Installs deps, tests, builds and deploys ALL bridge functions (api + canvasSync). Does not touch secrets.
rem Deploys to the default project in .firebaserc (run "firebase use <project-id>" once).
setlocal EnableExtensions
set "ROOT=%~dp0.."
set "LOG=%~dp0..\deploy-functions.log"
echo ==== %DATE% %TIME% ==== > "%LOG%"
set "DRV="
for %%D in (Q R S T U V W X Y) do if not defined DRV if not exist %%D:\ set "DRV=%%D:"
if not defined DRV (echo No free drive letter>> "%LOG%" & goto fail)
for %%P in ("%ROOT%") do subst %DRV% "%%~fP" >> "%LOG%" 2>&1 || goto fail
cd /d "%DRV%\bridge" || goto fail
echo [1/4] npm ci>> "%LOG%"
call npm ci --include=dev >> "%LOG%" 2>&1 || goto fail
echo [2/4] tests>> "%LOG%"
call npm test >> "%LOG%" 2>&1 || goto fail
echo [3/4] build>> "%LOG%"
call npm run build >> "%LOG%" 2>&1 || goto fail
echo [4/4] deploy>> "%LOG%"
cd /d "%DRV%\" || goto fail
call "%DRV%\bridge\node_modules\.bin\firebase.cmd" deploy --only functions --non-interactive >> "%LOG%" 2>&1 || goto fail
echo DEPLOY_OK>> "%LOG%"
cd /d "%ROOT%"
subst %DRV% /d
exit /b 0
:fail
echo DEPLOY_FAILED>> "%LOG%"
cd /d "%ROOT%" 2>nul
if defined DRV subst %DRV% /d 2>nul
exit /b 1
