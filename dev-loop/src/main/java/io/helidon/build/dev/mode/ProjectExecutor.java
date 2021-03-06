/*
 * Copyright (c) 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.build.dev.mode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.helidon.build.dev.Project;
import io.helidon.build.util.Constants;
import io.helidon.build.util.Log;
import io.helidon.build.util.ProcessMonitor;

import static io.helidon.build.util.DevLoopMessages.DEV_LOOP_SERVER_STARTING;
import static io.helidon.build.util.DevLoopMessages.DEV_LOOP_SERVER_STOPPED;
import static io.helidon.build.util.DevLoopMessages.DEV_LOOP_SERVER_STOPPING;
import static io.helidon.build.util.Style.BoldBrightCyan;
import static io.helidon.build.util.Style.BoldBrightGreen;
import static io.helidon.build.util.Style.BoldBrightRed;
import static io.helidon.build.util.Style.BoldYellow;

/**
 * Project executor.
 */
public class ProjectExecutor {
    private static final long STOP_WAIT_SECONDS = 1L;
    private static final int STOP_WAIT_RETRIES = 5;
    private static final int STOP_WAIT_RETRY_LOG_STEP = 1;
    private static final int STOP_WAIT_RETRY_FORCE_STEP = 3;

    private static final String MAVEN_EXEC = Constants.OS.mavenExec();
    private static final List<String> EXEC_COMMAND = List.of(MAVEN_EXEC, "exec:java");
    private static final String JAVA_EXEC = Constants.OS.javaExecutable();
    private static final String JAVA_HOME = Constants.javaHome();
    private static final String JAVA_HOME_BIN = JAVA_HOME + File.separator + "bin";
    private static final String JIT_LEVEL_ONE = "-XX:TieredStopAtLevel=1";
    private static final String JIT_TWO_COMPILER_THREADS = "-XX:CICompilerCount=2";
    private static final String STARTING = BoldBrightGreen.apply(DEV_LOOP_SERVER_STARTING);
    private static final String STOPPING = BoldYellow.apply(DEV_LOOP_SERVER_STOPPING);
    private static final String STOPPED = BoldBrightRed.apply(DEV_LOOP_SERVER_STOPPED);

    /**
     * Execution mode.
     */
    public enum ExecutionMode {
        /**
         * Execute project via java.
         */
        JAVA,

        /**
         * Execute project via maven.
         */
        MAVEN
    }

    private final ExecutionMode mode;
    private final Project project;
    private final String logPrefix;
    private final String name;
    private ProcessMonitor processMonitor;
    private long pid;
    private boolean hasStdOutMessage;
    private boolean hasStdErrMessage;

    /**
     * Create an executor from a project.
     *
     * @param project The project.
     * @param logPrefix The log prefix.
     */
    public ProjectExecutor(Project project, String logPrefix) {
        this(project, ExecutionMode.JAVA, logPrefix);
    }

    /**
     * Create an executor from a project specifying an execution mode.
     *
     * @param project The project.
     * @param logPrefix The log prefix.
     * @param mode The execution mode.
     */
    public ProjectExecutor(Project project, ExecutionMode mode, String logPrefix) {
        this.project = project;
        this.mode = mode;
        this.logPrefix = logPrefix;
        this.name = BoldBrightCyan.apply(project.name());
    }

    /**
     * Get project instance.
     *
     * @return The project.
     */
    public Project project() {
        return project;
    }

    /**
     * Start execution.
     */
    public void start() {
        switch (mode) {
            case JAVA:
                startJava();
                break;
            case MAVEN:
                startMaven();
                break;
            default:
                throw new InternalError("Unrecognized mode " + mode);
        }
    }

    /**
     * Stop execution. Logs stopping message only if process does not stop quickly.
     *
     * @throws IllegalStateException If process does not stop before timeout.
     */
    public void stop() {
        stop(false);
    }

    /**
     * Stop execution.
     *
     * @param verbose {@code true} if should log all state changes.
     * @throws IllegalStateException If process does not stop before timeout.
     */
    public void stop(boolean verbose) {
        if (processMonitor != null) {
            if (verbose) {
                stateChanged(STOPPING);
            }
            try {
                boolean isAlive = true;
                boolean force = false;
                for (int step = 0; step < STOP_WAIT_RETRIES; step++) {
                    try {
                        isAlive = processMonitor.destroy(force)
                                                .waitForCompletion(STOP_WAIT_SECONDS, TimeUnit.SECONDS)
                                                .isAlive();
                        if (!isAlive) {
                            break;
                        }
                    } catch (ProcessMonitor.ProcessTimeoutException timeout) {
                        if (!verbose && step == STOP_WAIT_RETRY_LOG_STEP) {
                            stateChanged(STOPPING);
                        } else if (step == STOP_WAIT_RETRY_FORCE_STEP) {
                            force = true;
                        }
                    } catch (IllegalStateException | ProcessMonitor.ProcessFailedException done) {
                        isAlive = false;
                        break;
                    } catch (Exception e) {
                        throw new IllegalStateException(stopFailedMessage(e.getMessage()));
                    }
                }
                if (isAlive) {
                    throw new IllegalStateException(stopFailedMessage("timeout expired"));
                }
            } finally {
                processMonitor = null;
            }
            if (verbose) {
                stateChanged(STOPPED);
            }
        }
    }

    /**
     * Check if project is running.
     *
     * @return {@code true} if running.
     */
    public boolean isRunning() {
        return processMonitor != null
               && processMonitor.isAlive();
    }

    /**
     * Check if project has printed to {@link System#out}.
     *
     * @return {@code true} if anything has been printed to {@link System#out}.
     */
    public boolean hasStdOutMessage() {
        return hasStdErrMessage;
    }

    /**
     * Check if project has printed to {@link System#err}.
     *
     * @return {@code true} if anything has been printed to {@link System#err}.
     */
    public boolean hasStdErrMessage() {
        return hasStdErrMessage;
    }

    private String stopFailedMessage(String reason) {
        return String.format("Failed to stop %s (pid %d): %s", project.name(), pid, reason);
    }

    private void stateChanged(String state) {
        if (logPrefix == null) {
            Log.info("%s %s", name, state);
        } else {
            Log.info("%s%s %s", logPrefix, name, state);
        }
    }

    private void startMaven() {
        start(EXEC_COMMAND);
    }

    private void startJava() {
        List<String> command = new ArrayList<>();
        command.add(JAVA_EXEC);
        command.add(JIT_LEVEL_ONE);             // Faster startup but longer warmup to peak perf
        command.add(JIT_TWO_COMPILER_THREADS);  // Faster startup but longer warmup to peak perf
        command.add("-cp");
        command.add(classPathString());
        command.add(project.mainClassName());
        start(command);
    }

    private void start(List<String> command) {
        hasStdErrMessage = false;
        ProcessBuilder processBuilder = new ProcessBuilder()
            .directory(project.root().path().toFile())
            .command(command);

        Map<String, String> env = processBuilder.environment();
        String path = JAVA_HOME_BIN + File.pathSeparatorChar + env.get("PATH");
        env.put("PATH", path);
        env.put("JAVA_HOME", JAVA_HOME);

        try {
            stateChanged(STARTING);
            Log.info();
            this.processMonitor = ProcessMonitor.builder()
                                                .processBuilder(processBuilder)
                                                .stdOut(this::printStdOut)
                                                .stdErr(this::printStdErr)
                                                .capture(true)
                                                .build()
                                                .start();
            this.pid = processMonitor.toHandle().pid();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void printStdOut(String line) {
        hasStdOutMessage = true;
        System.out.println(line);
    }

    private void printStdErr(String line) {
        hasStdErrMessage = true;
        System.err.println(line);
    }

    private String classPathString() {
        List<String> paths = project.classpath().stream()
                                    .map(File::getAbsolutePath).collect(Collectors.toList());
        return paths.stream().reduce("", (s1, s2) -> s1 + ":" + s2);
    }
}
