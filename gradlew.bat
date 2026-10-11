@rem Gradle wrapper batch script (simplified).
@echo off
set APP_HOME=%~dp0
if defined JAVA_HOME (
    set JAVACMD=%JAVA_HOME%\bin\java.exe
) else (
    set JAVACMD=java.exe
)
"%JAVACMD%" -Xmx64m -Xms64m -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
