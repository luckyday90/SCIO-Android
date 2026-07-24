@ECHO OFF
SETLOCAL
SET APP_HOME=%~dp0
SET WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
IF NOT EXIST "%WRAPPER_JAR%" (
  ECHO Errore: gradle\wrapper\gradle-wrapper.jar non trovato. 1>&2
  EXIT /B 1
)
IF DEFINED JAVA_HOME (SET JAVA_EXE=%JAVA_HOME%\bin\java.exe) ELSE (SET JAVA_EXE=java.exe)
"%JAVA_EXE%" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
ENDLOCAL
