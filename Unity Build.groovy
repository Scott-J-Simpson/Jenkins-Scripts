```groovy
// Jenkinsfile
pipeline {
    agent {
        label 'windows-unity' // Node with Unity installed
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
        UNITY_EDITOR       = "${params.UNITY_INSTALL_PATH}\\${params.UNITY_VERSION}\\Editor\\Unity.exe"
        PROJECT_PATH       = "${WORKSPACE}\\UnityProject"
        BUILD_OUTPUT_DIR   = "${WORKSPACE}\\Build"
        BUILD_EXECUTABLE   = "${BUILD_OUTPUT_DIR}\\GameBuild.exe"
        BUILD_LOG          = "${WORKSPACE}\\Logs\\unity_build.log"
        RUN_LOG            = "${WORKSPACE}\\Logs\\unity_run.log"
        PROFILER_LOG       = "${WORKSPACE}\\Logs\\profiler.log"
        PROFILER_DATA_DIR  = "${WORKSPACE}\\ProfilerData"
        PROFILER_PORT      = "34999"
    }

    options {
        timestamps()
        timeout(time: 60, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
    }

    stages {

        // =====================================================================
        // STAGE 1: Validate Environment
        // =====================================================================
        stage('Validate Environment') {
            steps {
                script {
                    echo "============================================"
                    echo " Validating Unity Installation & Environment"
                    echo "============================================"

                    // Verify Unity executable exists
                    if (!fileExists(env.UNITY_EDITOR)) {
                        error(
                            "Unity editor not found at: ${env.UNITY_EDITOR}\n" +
                            "Ensure Unity ${params.UNITY_VERSION} is installed on this agent."
                        )
                    }
                    echo "Unity found at: ${env.UNITY_EDITOR}"

                    // Get Unity version for verification
                    bat """
                        "${env.UNITY_EDITOR}" -quit -batchmode -version 2>&1 || echo "Version check complete"
                    """
                }
            }
        }

        // =====================================================================
        // STAGE 2: Checkout Source Code
        // =====================================================================
        stage('Checkout') {
            steps {
                script {
                    echo "============================================"
                    echo " Checking out branch: ${params.BRANCH_NAME}"
                    echo "============================================"
                }

                // Clean workspace before checkout
                cleanWs()

                // Clone the repository at the specified branch (latest commit)
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

                // Capture the commit hash for traceability
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

        // =====================================================================
        // STAGE 3: Prepare Build Infrastructure
        // =====================================================================
        stage('Prepare Build') {
            steps {
                script {
                    echo "============================================"
                    echo " Preparing Build Directories & Scripts"
                    echo "============================================"

                    // Create output directories
                    bat """
                        if not exist "${env.BUILD_OUTPUT_DIR}" mkdir "${env.BUILD_OUTPUT_DIR}"
                        if not exist "${WORKSPACE}\\Logs" mkdir "${WORKSPACE}\\Logs"
                        if not exist "${env.PROFILER_DATA_DIR}" mkdir "${env.PROFILER_DATA_DIR}"
                    """

                    // Write the C# build script into the Unity project
                    // This script is invoked by Unity in batchmode
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
        // Development Build:         Includes debug symbols and development features
        // ConnectWithProfiler:       Auto-connects to the Unity Profiler on launch
        // EnableDeepProfilingSupport: Enables deep profiling for detailed call stacks
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

        // =====================================================================
        // STAGE 4: Build the Unity Project
        // =====================================================================
        stage('Build Unity Project') {
            steps {
                script {
                    echo "============================================"
                    echo " Building Unity Project (Windows x64)"
                    echo " Development Build: ON"
                    echo " Autoconnect Profiler: ON"
                    echo " Deep Profiling Support: ON"
                    echo "============================================"

                    // Build command breakdown:
                    //   -quit           : Exit Unity when done
                    //   -batchmode      : Run without the GUI
                    //   -nographics     : No GPU needed for build step
                    //   -projectPath    : Path to the Unity project
                    //   -executeMethod  : C# static method to invoke
                    //   -buildTarget    : Target platform
                    //   -buildOutput    : Custom arg parsed by our script
                    //   -logFile        : Where to write the editor log

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

                    // Always archive the build log
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

                    // Verify the executable was produced
                    if (!fileExists(env.BUILD_EXECUTABLE)) {
                        error("Build executable not found at: ${env.BUILD_EXECUTABLE}")
                    }

                    echo "Build successful! Executable: ${env.BUILD_EXECUTABLE}"
                }
            }
        }

        // =====================================================================
        // STAGE 5: Launch Standalone Profiler
        // =====================================================================
        stage('Launch Profiler') {
            steps {
                script {
                    echo "============================================"
                    echo " Launching Unity Standalone Profiler"
                    echo " Listening on port: ${env.PROFILER_PORT}"
                    echo "============================================"

                    // The Unity Profiler can be launched as a standalone window
                    // using the -profilerconnection flag. It listens for
                    // incoming connections from development builds.

                    // Launch profiler in background (non-blocking)
                    bat """
                        start "UnityProfiler" /B ^
                            "${env.UNITY_EDITOR}" ^
                                -profilerconnection "127.0.0.1:${env.PROFILER_PORT}" ^
                                -logFile "${env.PROFILER_LOG}" ^
                                -openProfiler
                    """

                    // Give the profiler time to initialize and start listening
                    echo "Waiting for Profiler to initialize..."
                    sleep(time: 15, unit: 'SECONDS')

                    echo "Profiler should now be listening for connections."
                }
            }
        }

        // =====================================================================
        // STAGE 6: Run the Build with Test Arguments
        // =====================================================================
        stage('Run Build & Profile') {
            steps {
                script {
                    echo "============================================"
                    echo " Running Built Executable"
                    echo " Command line args: -mode fpsTest -pos 1,1,1"
                    echo " Profiler connection: 127.0.0.1:${env.PROFILER_PORT}"
                    echo " Duration: ${params.PROFILER_DURATION_SECONDS}s"
                    echo "============================================"

                    // Launch the built game executable with:
                    //   Custom args:           -mode fpsTest -pos 1,1,1
                    //   Profiler connection:   Auto-connects to the standalone profiler
                    //
                    // Because we built with ConnectWithProfiler and
                    // EnableDeepProfilingSupport, the player will automatically
                    // attempt to connect to a profiler at launch.

                    bat """
                        start "GameBuild" /B ^
                            "${env.BUILD_EXECUTABLE}" ^
                                -mode fpsTest ^
                                -pos 1,1,1 ^
                                -profiler-enable ^
                                -deepprofiling ^
                                -profiler-log-file "${env.PROFILER_DATA_DIR}\\profiler_output.raw" ^
                                -logFile "${env.RUN_LOG}"
                    """

                    echo "Build is running. Capturing profiler data for ${params.PROFILER_DURATION_SECONDS} seconds..."

                    // Let the application run for the specified duration
                    // while the profiler captures performance data
                    sleep(time: params.PROFILER_DURATION_SECONDS.toInteger(), unit: 'SECONDS')

                    echo "Profiling capture period complete."
                }
            }
        }

        // =====================================================================
        // STAGE 7: Stop Processes & Collect Results
        // =====================================================================
        stage('Collect Results') {
            steps {
                script {
                    echo "============================================"
                    echo " Stopping Processes & Collecting Profiler Data"
                    echo "============================================"

                    // Gracefully terminate the game process
                    bat '''
                        taskkill /IM "GameBuild.exe" /F 2>nul || echo "Game process already stopped"
                    '''

                    // Wait a moment for file handles to release
                    sleep(time: 5, unit: 'SECONDS')

                    // Terminate the profiler
                    bat '''
                        taskkill /IM "Unity.exe" /F 2>nul || echo "Profiler process already stopped"
                    '''

                    // Display run log summary
                    if (fileExists(env.RUN_LOG)) {
                        echo "====== APPLICATION RUN LOG (last 50 lines) ======"
                        bat "powershell -Command \"Get-Content '${env.RUN_LOG}' -Tail 50\""
                    }

                    // Check if profiler data was generated
                    bat """
                        echo "Profiler data directory contents:"
                        dir "${env.PROFILER_DATA_DIR}" 2>nul || echo "No profiler data files found"
                    """
                }
            }
        }
    }

    // =========================================================================
    // POST-BUILD ACTIONS
    // =========================================================================
    post {
        always {
            script {
                echo "============================================"
                echo " Archiving Artifacts & Cleaning Up"
                echo "============================================"

                // Ensure stray processes are cleaned up
                bat '''
                    taskkill /IM "GameBuild.exe" /F 2>nul || exit /b 0
                '''
            }

            // Archive build logs for debugging
            archiveArtifacts(
                artifacts: 'Logs/**/*.log',
                allowEmptyArchive: true,
                fingerprint: true
            )

            // Archive profiler data
            archiveArtifacts(
                artifacts: 'ProfilerData/**/*',
                allowEmptyArchive: true,
                fingerprint: true
            )

            // Archive the built executable and data
            archiveArtifacts(
                artifacts: 'Build/**/*',
                allowEmptyArchive: true,
                fingerprint: true
            )
        }

        success {
            echo """
            =============================================
             BUILD & PROFILE COMPLETED SUCCESSFULLY
            =============================================
             Branch:  ${params.BRANCH_NAME}
             Commit:  ${env.GIT_COMMIT_SHORT ?: 'N/A'}
             Message: ${env.GIT_COMMIT_MSG ?: 'N/A'}
             Build:   ${env.BUILD_EXECUTABLE}
            =============================================
            """
        }

        failure {
            echo """
            =============================================
             BUILD OR PROFILE FAILED
            =============================================
             Branch:  ${params.BRANCH_NAME}
             Commit:  ${env.GIT_COMMIT_SHORT ?: 'N/A'}
             Check the archived logs for details.
            =============================================
            """

            // Optional: Send failure notification
            // mail(
            //     to: 'team@example.com',
            //     subject: "Unity Build Failed: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
            //     body: "Branch: ${params.BRANCH_NAME}\nCommit: ${env.GIT_COMMIT_SHORT}\n${env.BUILD_URL}"
            // )
        }

        cleanup {
            // Clean workspace to free disk space (optional)
            // cleanWs()
            echo "Pipeline execution complete."
        }
    }
}
```