// Jenkinsfile
pipeline {
    agent {
        label 'windows-unity' 
    }

    parameters {
        string(
            name: 'BRANCH_NAME',
            defaultValue: 'master',
            description: 'The Git branch to build from'
        )
        string(
            name: 'UNITY_VERSION',
            defaultValue: '6000.4.7f1',
            description: 'Unity version to use'
        )
        string(
            name: 'UNITY_INSTALL_PATH',
            defaultValue: 'C:\\Program Files\\Unity\\Hub\\Editor',
            description: 'Base path where Unity versions are installed'
        )
        string(
            name: 'GITHUB_REPO_URL',
            defaultValue: 'https://github.com/Scott-J-Simpson/FPS-Reporter.git',
            description: 'GitHub repository URL'
        )
        string(
            name: 'PROFILER_DURATION_SECONDS',
            defaultValue: '30',
            description: 'How long to let the profiler capture data (in seconds)'
        )
    }

    environment {
        UNITY_EDITOR = "${params.UNITY_INSTALL_PATH}\\${params.UNITY_VERSION}\\Editor\\Unity.exe"
        PROJECT_PATH = "${WORKSPACE}\\UnityProject"
        BUILD_OUTPUT_DIR = "${WORKSPACE}\\Build"
        BUILD_EXECUTABLE = "${BUILD_OUTPUT_DIR}\\GameBuild.exe"
        BUILD_LOG = "${WORKSPACE}\\Logs\\unity_build.log"
        RUN_LOG = "${WORKSPACE}\\Logs\\unity_run.log"
        PROFILER_DATA_DIR = "${WORKSPACE}\\ProfilerData"
        // NOTE: PROFILER_RAW_FILE is intentionally NOT declared here. It is set
        // dynamically (with a datetime stamp) in the 'Prepare Build' stage.
        // Declaring it in this environment{} block would let the directive value
        // take precedence and silently override the dynamic assignment.
    }

    options {
        timestamps()
        timeout(time: 60, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
    }

    stages {

        // STAGE 1: Validate Environment
        stage('Validate Environment') {
            steps {
                script {
                    echo "============================================"
                    echo " Validating Unity Installation & Environment"
                    echo "============================================"

                    // def sessionCheck = bat(
                    //     script: 'net session >nul 2>&1 && echo ADMIN || echo USER',
                    //     returnStdout: true
                    // ).trim().split('\n').last().trim()

                    // if (sessionCheck == 'ADMIN') {
                    //     env.NEEDS_DEELEVATION = 'true'
                    // } else {
                    //     env.NEEDS_DEELEVATION = 'false'
                    // }

                    env.NEEDS_DEELEVATION = 'false'

                    if (!fileExists(env.UNITY_EDITOR)) {
                        error(
                            "Unity editor not found at: ${env.UNITY_EDITOR}\n" +
                            "Ensure Unity ${params.UNITY_VERSION} is installed."
                        )
                    }
                    echo "Unity found at: ${env.UNITY_EDITOR}"
                }
            }
        }

        // STAGE 2: Checkout Source Code
        stage('Checkout') {
            steps {
                script {
                    echo "============================================"
                    echo " Checking out branch: ${params.BRANCH_NAME}"
                    echo "============================================"
                }

                cleanWs()
                
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: "*/${params.BRANCH_NAME}"]],
                    extensions: [
                        [$class: 'RelativeTargetDirectory',
                         relativeTargetDir: 'UnityProject'],
                        [$class: 'CloneOption',
                         depth: 1,
                         noTags: true,
                         shallow: true],
                        [$class: 'CleanBeforeCheckout'],
                        [$class: 'PruneStaleBranch']
                    ],
                    userRemoteConfigs: [[
                        url: params.GITHUB_REPO_URL,
                        credentialsId: 'github-credentials'
                    ]]
                ])
                
                script {
                    dir('UnityProject') {
                        env.GIT_COMMIT_HASH = bat(
                            script: 'git rev-parse HEAD',
                            returnStdout: true
                        ).trim().split('\n').last().trim()

                        env.GIT_COMMIT_SHORT = bat(
                            script: 'git rev-parse --short HEAD',
                            returnStdout: true
                        ).trim().split('\n').last().trim()

                        env.GIT_COMMIT_MSG = bat(
                            script: 'git log -1 --pretty=%%s',
                            returnStdout: true
                        ).trim().split('\n').last().trim()

                        echo "Checked out commit: ${env.GIT_COMMIT_SHORT}"
                        echo "Commit message: ${env.GIT_COMMIT_MSG}"
                    }
                }
            }
        }

        // STAGE 3: Prepare Build Infrastructure
        stage('Prepare Build') {
            steps {
                script {
                    echo "============================================"
                    echo " Preparing Build Directories & Scripts"
                    echo "============================================"

                    bat """
                        if not exist "${env.BUILD_OUTPUT_DIR}" mkdir "${env.BUILD_OUTPUT_DIR}"
                        if not exist "${WORKSPACE}\\Logs" mkdir "${WORKSPACE}\\Logs"
                        if not exist "${env.PROFILER_DATA_DIR}" mkdir "${env.PROFILER_DATA_DIR}"
                    """

                    // Append a datetime stamp so each run produces a uniquely
                    // named profiler capture (e.g. profiler_output_20260720_143512.raw)
                    def profilerTimestamp = bat(
                        script: '@echo off\r\npowershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"',
                        returnStdout: true
                    ).trim().split('\n').last().trim()

                    env.PROFILER_RAW_FILE =
                        "${env.PROFILER_DATA_DIR}\\profiler_output_${profilerTimestamp}.raw"

                    echo "Profiler output will be written to: ${env.PROFILER_RAW_FILE}"

                    // Write the C# build script into the Unity project
                    writeFile file: "${env.PROJECT_PATH}\\Assets\\Editor\\JenkinsBuildScript.cs",
                        text: '''
using UnityEditor;
using UnityEngine;
using System;
using System.Linq;

public static class JenkinsBuildScript
{
    /// <summary>
    /// Entry point called by Jenkins via -executeMethod.
    /// Builds a Windows Standalone player with Development + Profiler settings.
    /// </summary>
    public static void PerformBuild()
    {
        Debug.Log("[JenkinsBuild] Starting build process...");

        // ---- Parse command-line arguments ----
        string[] args = Environment.GetCommandLineArgs();
        string buildPath = GetArgValue(args, "-buildOutput");

        if (string.IsNullOrEmpty(buildPath))
        {
            buildPath = "Build/GameBuild.exe";
            Debug.LogWarning(
                "[JenkinsBuild] No -buildOutput specified. " +
                "Defaulting to: " + buildPath
            );
        }

        Debug.Log("[JenkinsBuild] Build output path: " + buildPath);

        // ---- Gather all enabled scenes ----
        string[] scenes = EditorBuildSettings.scenes
            .Where(s => s.enabled)
            .Select(s => s.path)
            .ToArray();

        if (scenes.Length == 0)
        {
            Debug.LogError(
                "[JenkinsBuild] No scenes found in Build Settings! " +
                "Add scenes to File > Build Settings."
            );
            EditorApplication.Exit(1);
            return;
        }

        Debug.Log("[JenkinsBuild] Building with " + scenes.Length + " scene(s):");
        foreach (string scene in scenes)
        {
            Debug.Log("  - " + scene);
        }

        // ---- Configure Build Options ----
        //
        // Development:                 Enables development build features
        // ConnectWithProfiler:         Player attempts to auto-connect to a
        //                              profiler on launch (will silently fail
        //                              if no profiler is listening — this is
        //                              fine for file-based capture)
        // EnableDeepProfilingSupport:  Instruments all mono method calls for
        //                              detailed profiling call stacks
        BuildOptions buildOptions =
            BuildOptions.Development |
            BuildOptions.ConnectWithProfiler |
            BuildOptions.EnableDeepProfilingSupport;

        Debug.Log("[JenkinsBuild] Build options: " + buildOptions.ToString());

        // ---- Execute Build ----
        BuildPlayerOptions buildPlayerOptions = new BuildPlayerOptions
        {
            scenes           = scenes,
            locationPathName = buildPath,
            target           = BuildTarget.StandaloneWindows64,
            options           = buildOptions
        };

        UnityEditor.Build.Reporting.BuildReport report =
            BuildPipeline.BuildPlayer(buildPlayerOptions);

        UnityEditor.Build.Reporting.BuildSummary summary = report.summary;

        Debug.Log("[JenkinsBuild] Build result: " + summary.result);
        Debug.Log("[JenkinsBuild] Total time: " + summary.totalTime);
        Debug.Log("[JenkinsBuild] Total size: " + summary.totalSize + " bytes");
        Debug.Log("[JenkinsBuild] Total warnings: " + summary.totalWarnings);
        Debug.Log("[JenkinsBuild] Total errors: " + summary.totalErrors);

        if (summary.result != UnityEditor.Build.Reporting.BuildResult.Succeeded)
        {
            Debug.LogError("[JenkinsBuild] Build FAILED!");
            EditorApplication.Exit(1);
            return;
        }

        Debug.Log("[JenkinsBuild] Build SUCCEEDED!");
        EditorApplication.Exit(0);
    }

    /// <summary>
    /// Parses a command-line argument value by key.
    /// Example: -buildOutput "C:\\Build\\Game.exe"
    /// </summary>
    private static string GetArgValue(string[] args, string key)
    {
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i].Equals(key, StringComparison.OrdinalIgnoreCase)
                && i + 1 < args.Length)
            {
                return args[i + 1];
            }
        }
        return null;
    }
}
'''
                    echo "Build script written to Assets/Editor/JenkinsBuildScript.cs"
                }
            }
        }

        // STAGE 4: Build the Unity Project
        stage('Build Unity Project') {
            steps {
                script {
                    echo "============================================"
                    echo " Building Unity Project (Windows x64)"
                    echo " Development Build: ON"
                    echo " Autoconnect Profiler: ON"
                    echo " Deep Profiling Support: ON"
                    echo "============================================"

                    def buildExitCode = bat(
                        script: """
                            "${env.UNITY_EDITOR}" ^
                                -quit ^
                                -batchmode ^
                                -nographics ^
                                -projectPath "${env.PROJECT_PATH}" ^
                                -executeMethod JenkinsBuildScript.PerformBuild ^
                                -buildTarget Win64 ^
                                -buildOutput "${env.BUILD_EXECUTABLE}" ^
                                -logFile "${env.BUILD_LOG}"
                        """,
                        returnStatus: true
                    )

                    if (fileExists(env.BUILD_LOG)) {
                        echo "====== UNITY BUILD LOG (last 100 lines) ======"
                        bat "powershell -Command \"Get-Content '${env.BUILD_LOG}' -Tail 100\""
                    }

                    if (buildExitCode != 0) {
                        error(
                            "Unity build failed with exit code: ${buildExitCode}. " +
                            "Check the full build log for details."
                        )
                    }

                    if (!fileExists(env.BUILD_EXECUTABLE)) {
                        error("Build executable not found at: ${env.BUILD_EXECUTABLE}")
                    }

                    echo "Build successful! Executable: ${env.BUILD_EXECUTABLE}"
                }
            }
        }

        // STAGE 5: Run the Build with Test Arguments & File-Based Profiling
        stage('Run Build & Capture Profiler Data') {
            steps {
                script {
                    echo "============================================"
                    echo " Running Built Executable"
                    echo " Command line args: -mode fpsTest -pos 1,1,1"
                    echo " Profiler output:   ${env.PROFILER_RAW_FILE}"
                    echo " Duration:          ${params.PROFILER_DURATION_SECONDS}s"
                    echo "============================================"
        
                    def gameArgs = [
                        '-mode fpsTest',
                        '-pos 1,1,1',
                        '-profiler-enable',
                        '-deepprofiling',
                        "-profiler-log-file \"${env.PROFILER_RAW_FILE}\"",
                        '-profiler-maxusedmemory 536870912',
                        "-logFile \"${env.RUN_LOG}\""
                    ].join(' ')
        
                    if (env.NEEDS_DEELEVATION == 'true') {
                        echo "Launching with de-elevation (runas /trustlevel:0x20000)"
        
                        writeFile file: "${WORKSPACE}\\run_game.bat", text: """\
        @echo off
        echo Launching game executable (de-elevated)...
        "${env.BUILD_EXECUTABLE}" ${gameArgs}
        echo Game process exited with code: %ERRORLEVEL%
        """
        
                        bat "runas /trustlevel:0x20000 \"${WORKSPACE}\\run_game.bat\""
        
                    } else {
                        echo "Launching directly (already non-elevated)"
        
                        writeFile file: "${WORKSPACE}\\run_game.bat", text: """\
        @echo off
        echo Launching game executable...
        "${env.BUILD_EXECUTABLE}" ${gameArgs}
        echo Game process exited with code: %ERRORLEVEL%
        """
        
                        bat "start \"GameBuild\" /B \"${WORKSPACE}\\run_game.bat\""
                    }
        
                    echo "Build is running. Capturing profiler data for ${params.PROFILER_DURATION_SECONDS} seconds..."
                    sleep(time: params.PROFILER_DURATION_SECONDS.toInteger(), unit: 'SECONDS')
        
                    echo "Capture period complete. Stopping the player..."
                    bat 'taskkill /F /IM "GameBuild.exe" 2>nul || exit /b 0'
        
                    sleep(time: 5, unit: 'SECONDS')
                }
            }
        }

        // STAGE 6: Validate & Collect Results
        stage('Collect Results') {
            steps {
                script {
                    echo "============================================"
                    echo " Collecting Results & Profiler Data"
                    echo "============================================"

                    if (fileExists(env.RUN_LOG)) {
                        echo "====== APPLICATION RUN LOG (last 50 lines) ======"
                        bat "powershell -Command \"Get-Content '${env.RUN_LOG}' -Tail 50\""
                    } else {
                        echo "WARNING: Application run log not found at ${env.RUN_LOG}"
                    }


                    if (fileExists(env.PROFILER_RAW_FILE)) {
                        def fileSizeOutput = bat(
                            script: "powershell -Command \"(Get-Item '${env.PROFILER_RAW_FILE}').Length\"",
                            returnStdout: true
                        ).trim().split('\n').last().trim()

                        def fileSizeBytes = fileSizeOutput.toLong()
                        def fileSizeMB = String.format("%.2f", fileSizeBytes / (1024.0 * 1024.0))

                        echo "Profiler .raw file found: ${env.PROFILER_RAW_FILE}"
                        echo "Profiler data size: ${fileSizeMB} MB (${fileSizeBytes} bytes)"

                        if (fileSizeBytes < 1024) {
                            echo "WARNING: Profiler data file is suspiciously small (<1 KB). " +
                                 "Profiling may not have captured correctly."
                        }
                    } else {
                        echo "WARNING: Profiler .raw file not found at ${env.PROFILER_RAW_FILE}"
                        echo "The player may have crashed before writing profiler data."
                    }

                    echo "====== PROFILER DATA DIRECTORY ======"
                    bat """
                        dir "${env.PROFILER_DATA_DIR}" 2>nul || exit /b 0
                    """
                }
            }
        }
    }

    // POST-BUILD ACTIONS
    post {
        always {
            script {
                echo "============================================"
                echo " Archiving Artifacts & Cleaning Up"
                echo "============================================"
                
                bat '''
                    taskkill /F /IM "GameBuild.exe" 2>nul || exit /b 0
                '''

                bat '''
                    taskkill /F /IM "Unity.exe" 2>nul || exit /b 0
                '''
            }

            archiveArtifacts(
                artifacts: 'Logs/**/*.log',
                allowEmptyArchive: true,
                fingerprint: true
            )

            archiveArtifacts(
                artifacts: 'ProfilerData/**/*',
                allowEmptyArchive: true,
                fingerprint: true
            )

            archiveArtifacts(
                artifacts: 'Build/**/*',
                allowEmptyArchive: true,
                fingerprint: true
            )
        }

        success {
            echo """
            =============================================
             BUILD & PROFILER CAPTURE COMPLETED
            =============================================
             Branch:        ${params.BRANCH_NAME}
             Commit:        ${env.GIT_COMMIT_SHORT ?: 'N/A'}
             Message:       ${env.GIT_COMMIT_MSG ?: 'N/A'}
             Build:         ${env.BUILD_EXECUTABLE}
             Profiler Data: ${env.PROFILER_RAW_FILE}

             To analyze the profiler data:
               1. Download the .raw file from Jenkins artifacts
               2. Open Unity Editor
               3. Window > Analysis > Profiler
               4. Click "Load" and select the .raw file
            =============================================
            """
        }

        failure {
            echo """
            =============================================
             BUILD OR RUN FAILED
            =============================================
             Branch:  ${params.BRANCH_NAME}
             Commit:  ${env.GIT_COMMIT_SHORT ?: 'N/A'}
             Check the archived logs for details.
            =============================================
            """
        }

        cleanup {
            echo "Pipeline execution complete."
        }
    }
}