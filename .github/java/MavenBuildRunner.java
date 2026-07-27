import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MavenBuildRunner {

    private static final Path DEFAULT_LOG_FILE = Path.of("build.log");
    private static final int DEFAULT_TAIL_LINES = 2_000;
    private static final long OUTPUT_CLOSE_TIMEOUT_SECONDS = 10;
    private static final long PROCESS_POLL_SECONDS = 1;

    private static final List<String> DEFAULT_BUILD_ARGUMENTS =
            List.of("clean", "verify", "-B", "-T", "1.5C", "-U");

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");

    private static final Path WORKING_DIRECTORY =
            Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    private static final MavenExecutable MAVEN_EXECUTABLE = MavenExecutable.detect();

    record Configuration(
            Path logFile,
            int tailLines,
            String heading,
            List<String> mavenArguments,
            boolean showHelp) {

        static Configuration parse(String[] args) {
            Path logFile = DEFAULT_LOG_FILE;
            int tailLines = DEFAULT_TAIL_LINES;
            String heading = null;
            List<String> mavenArguments = new ArrayList<>();
            boolean showHelp = false;

            for (int i = 0; i < args.length; i++) {
                String argument = args[i];

                if ("--".equals(argument)) {
                    for (int j = i + 1; j < args.length; j++) {
                        mavenArguments.add(args[j]);
                    }
                    break;
                }

                if ("--help".equals(argument) || "-h".equals(argument)) {
                    showHelp = true;
                    continue;
                }

                if ("--log-file".equals(argument)) {
                    logFile = Path.of(requireValue(args, ++i, "--log-file"));
                    continue;
                }

                if (argument.startsWith("--log-file=")) {
                    logFile = Path.of(nonBlankValue(
                            argument.substring("--log-file=".length()), "--log-file"));
                    continue;
                }

                if ("--tail-lines".equals(argument)) {
                    tailLines = parsePositiveInt(
                            requireValue(args, ++i, "--tail-lines"), "--tail-lines");
                    continue;
                }

                if (argument.startsWith("--tail-lines=")) {
                    tailLines = parsePositiveInt(
                            nonBlankValue(
                                    argument.substring("--tail-lines=".length()), "--tail-lines"),
                            "--tail-lines");
                    continue;
                }

                if ("--heading".equals(argument)) {
                    heading = requireValue(args, ++i, "--heading");
                    continue;
                }

                if (argument.startsWith("--heading=")) {
                    heading = nonBlankValue(
                            argument.substring("--heading=".length()), "--heading");
                    continue;
                }

                // Preserve the old interface: the first non-runner option starts
                // the Maven argument list. Use "--" when a Maven argument could
                // otherwise be mistaken for a runner option.
                for (int j = i; j < args.length; j++) {
                    mavenArguments.add(args[j]);
                }
                break;
            }

            if (mavenArguments.isEmpty()) {
                mavenArguments = DEFAULT_BUILD_ARGUMENTS;
            }

            return new Configuration(
                    logFile,
                    tailLines,
                    heading,
                    List.copyOf(mavenArguments),
                    showHelp);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return nonBlankValue(args[index], option);
        }

        private static String nonBlankValue(String value, String option) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(option + " requires a non-empty value");
            }
            return value;
        }

        private static int parsePositiveInt(String value, String name) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < 1) {
                    throw new IllegalArgumentException(name + " must be greater than zero");
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        name + " must be a positive integer, but was: " + value, e);
            }
        }
    }

    static final class MavenExecutable {
        private final Path wrapper;
        private final String systemCommand;

        private MavenExecutable(Path wrapper, String systemCommand) {
            this.wrapper = wrapper;
            this.systemCommand = systemCommand;
        }

        static MavenExecutable detect() {
            String wrapperName = WINDOWS ? "mvnw.cmd" : "mvnw";

            List<Path> roots = new ArrayList<>();
            String githubWorkspace = System.getenv("GITHUB_WORKSPACE");
            if (githubWorkspace != null && !githubWorkspace.isBlank()) {
                roots.add(Path.of(githubWorkspace).toAbsolutePath().normalize());
            }
            if (!roots.contains(WORKING_DIRECTORY)) {
                roots.add(WORKING_DIRECTORY);
            }

            for (Path root : roots) {
                Path candidate = root.resolve(wrapperName);
                if (Files.isRegularFile(candidate)) {
                    return new MavenExecutable(candidate, null);
                }
            }

            return new MavenExecutable(null, WINDOWS ? "mvn.cmd" : "mvn");
        }

        Command command(List<String> arguments) {
            List<String> processArguments = new ArrayList<>();
            String displayExecutable;

            if (wrapper != null) {
                if (WINDOWS) {
                    processArguments.add("cmd.exe");
                    processArguments.add("/d");
                    processArguments.add("/s");
                    processArguments.add("/c");
                    processArguments.add(wrapper.toString());
                } else if (Files.isExecutable(wrapper)) {
                    processArguments.add(wrapper.toString());
                } else {
                    processArguments.add("sh");
                    processArguments.add(wrapper.toString());
                }
                displayExecutable = displayPath(wrapper);
            } else {
                if (WINDOWS) {
                    processArguments.add("cmd.exe");
                    processArguments.add("/d");
                    processArguments.add("/s");
                    processArguments.add("/c");
                }
                processArguments.add(systemCommand);
                displayExecutable = systemCommand;
            }

            processArguments.addAll(arguments);

            List<String> displayArguments = new ArrayList<>();
            displayArguments.add(displayExecutable);
            displayArguments.addAll(arguments);

            return new Command(
                    processArguments,
                    displayArguments.stream()
                            .map(MavenBuildRunner::quoteForDisplay)
                            .reduce((left, right) -> left + " " + right)
                            .orElse(""));
        }

        String description() {
            if (wrapper != null) {
                return "Maven wrapper (" + displayPath(wrapper) + ")";
            }
            return "system Maven (" + systemCommand + ")";
        }

        private static String displayPath(Path path) {
            if (path.getParent() != null && path.getParent().equals(WORKING_DIRECTORY)) {
                return WINDOWS ? ".\\" + path.getFileName() : "./" + path.getFileName();
            }
            return path.toString();
        }
    }

    static final class Command {
        private final List<String> processArguments;
        private final String displayValue;

        private Command(List<String> processArguments, String displayValue) {
            this.processArguments = List.copyOf(processArguments);
            this.displayValue = displayValue;
        }

        static Command maven(List<String> arguments) {
            return MAVEN_EXECUTABLE.command(arguments);
        }

        List<String> processArguments() {
            return processArguments;
        }

        @Override
        public String toString() {
            return displayValue;
        }
    }

    static final class TailBuffer {
        private final int maxCapacity;
        private final ArrayDeque<String> lines;

        TailBuffer(int maxCapacity) {
            if (maxCapacity < 1) {
                throw new IllegalArgumentException("maxCapacity must be positive");
            }
            this.maxCapacity = maxCapacity;
            this.lines = new ArrayDeque<>(maxCapacity);
        }

        synchronized void add(String line) {
            if (lines.size() >= maxCapacity) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }

        synchronized List<String> snapshot() {
            return List.copyOf(lines);
        }
    }

    static final class OutputPump {
        private final FutureTask<Void> task;
        private final Thread thread;

        OutputPump(FutureTask<Void> task, Thread thread) {
            this.task = task;
            this.thread = thread;
        }
    }

    static class CommandProcess {
        final ProcessBuilder builder;
        final Command command;
        private final boolean captureOutput;

        CommandProcess(Command command, boolean captureOutput) {
            this.command = command;
            this.captureOutput = captureOutput;
            this.builder = new ProcessBuilder(command.processArguments())
                    .directory(WORKING_DIRECTORY.toFile())
                    .redirectErrorStream(true);

            if (!captureOutput) {
                builder.inheritIO();
            }
        }

        int execute() throws Exception {
            Process process = builder.start();
            Set<ProcessHandle> knownDescendants = ConcurrentHashMap.newKeySet();

            // This is a non-interactive CI build. Closing stdin prevents Maven or
            // a plugin from waiting forever for input that the runner will never send.
            process.getOutputStream().close();

            Thread shutdownHook = new Thread(
                    () -> terminateProcessTree(process, knownDescendants),
                    "maven-process-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            OutputPump outputPump = captureOutput ? startOutputPump(process.getInputStream()) : null;

            try {
                int exitCode = waitForProcess(process, outputPump, knownDescendants);
                awaitOutputClose(process, outputPump, knownDescendants);

                if (exitCode == 0) {
                    handleSuccess();
                } else {
                    handleError();
                }
                return exitCode;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                terminateProcessTree(process, knownDescendants);
                throw e;
            } catch (Exception | Error e) {
                terminateProcessTree(process, knownDescendants);
                throw e;
            } finally {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // The JVM is already shutting down.
                }
            }
        }

        private OutputPump startOutputPump(InputStream inputStream) {
            FutureTask<Void> task = new FutureTask<>(() -> {
                handleInputStream(inputStream);
                return null;
            });
            Thread thread = new Thread(task, "maven-output-reader");
            thread.setDaemon(true);
            thread.start();
            return new OutputPump(task, thread);
        }

        private int waitForProcess(
                Process process,
                OutputPump outputPump,
                Set<ProcessHandle> knownDescendants) throws Exception {
            while (!process.waitFor(PROCESS_POLL_SECONDS, TimeUnit.SECONDS)) {
                process.descendants().forEach(knownDescendants::add);
                checkOutputPump(outputPump);
            }

            process.descendants().forEach(knownDescendants::add);
            checkOutputPump(outputPump);
            return process.exitValue();
        }

        private void awaitOutputClose(
                Process process,
                OutputPump outputPump,
                Set<ProcessHandle> knownDescendants) throws Exception {
            if (outputPump == null) {
                return;
            }

            try {
                outputPump.task.get(OUTPUT_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                System.err.printf(
                        "Maven exited, but its output stream did not close within %d seconds. "
                                + "Closing the stream and continuing.%n",
                        OUTPUT_CLOSE_TIMEOUT_SECONDS);

                destroyKnownDescendants(knownDescendants);
                closeQuietly(process.getInputStream());
                outputPump.thread.interrupt();
                outputPump.thread.join(TimeUnit.SECONDS.toMillis(1));
            } catch (ExecutionException e) {
                throw outputFailure(e);
            } catch (CancellationException e) {
                throw new IOException("Maven output processing was cancelled", e);
            }
        }

        private void checkOutputPump(OutputPump outputPump) throws Exception {
            if (outputPump == null || !outputPump.task.isDone()) {
                return;
            }

            try {
                outputPump.task.get();
            } catch (ExecutionException e) {
                throw outputFailure(e);
            } catch (CancellationException e) {
                throw new IOException("Maven output processing was cancelled", e);
            }
        }

        private Exception outputFailure(ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                return exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            return new IOException("Maven output processing failed", cause);
        }

        void handleInputStream(InputStream inputStream) throws Exception {
        }

        void handleError() {
        }

        void handleSuccess() {
        }
    }

    static final class MavenVersionCommandProcess extends CommandProcess {
        MavenVersionCommandProcess() {
            super(Command.maven(List.of("-v")), false);
        }
    }

    static final class MavenBuildCommandProcess extends CommandProcess {
        private static final Pattern BUILDING_PATTERN = Pattern.compile(
                "^\\[INFO\\] Building (?<name>.+) \\[(?<index>\\d+)/(?<size>\\d+)\\]$");

        private final Configuration configuration;
        private final Path logFile;
        private final int tailLines;
        private final TailBuffer lastLines;

        MavenBuildCommandProcess(Configuration configuration) {
            super(Command.maven(configuration.mavenArguments()), true);
            this.configuration = configuration;
            this.logFile = configuration.logFile();
            this.tailLines = configuration.tailLines();
            this.lastLines = new TailBuffer(tailLines);
        }

        @Override
        int execute() throws Exception {
            if (configuration.heading() != null) {
                System.out.println(configuration.heading());
                System.out.println();
            }

            System.out.println("+ " + command);
            System.out.println();
            return super.execute();
        }

        private void printProgress(String line) {
            Matcher matcher = BUILDING_PATTERN.matcher(line);
            if (!matcher.matches()) {
                return;
            }

            String index = matcher.group("index");
            String size = matcher.group("size");
            String name = matcher.group("name");
            String padding = " ".repeat(Math.max(0, size.length() - index.length()));
            System.out.printf("%s%s/%s| %s%n", padding, index, size, name);
        }

        @Override
        void handleInputStream(InputStream inputStream) throws IOException {
            IOException logFailure = null;
            BufferedWriter writer = null;

            try {
                Path parent = logFile.toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                writer = Files.newBufferedWriter(
                        logFile,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (IOException e) {
                logFailure = new IOException("Unable to open " + logFile, e);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    printProgress(line);
                    lastLines.add(line);

                    if (writer != null) {
                        try {
                            writer.write(line);
                            writer.newLine();
                        } catch (IOException e) {
                            logFailure = combine(
                                    logFailure,
                                    new IOException("Unable to write " + logFile, e));
                            closeQuietly(writer);
                            writer = null;
                        }
                    }
                }
            } catch (IOException e) {
                IOException readFailure = new IOException("Unable to read Maven output", e);
                if (logFailure != null) {
                    readFailure.addSuppressed(logFailure);
                }
                throw readFailure;
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException e) {
                        logFailure = combine(
                                logFailure,
                                new IOException("Unable to close " + logFile, e));
                    }
                }
            }

            if (logFailure != null) {
                throw logFailure;
            }
        }

        @Override
        void handleError() {
            System.out.println();
            lastLines.snapshot().forEach(System.out::println);
        }

        @Override
        void handleSuccess() {
            String separatorPrefix = "[INFO] " + "-".repeat(70);
            String summaryPrefix = "[INFO] Reactor Summary";
            List<String> lines = lastLines.snapshot();

            List<Integer> separators = new ArrayList<>();
            int summary = -1;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith(separatorPrefix)) {
                    separators.add(i);
                }
                if (line.startsWith(summaryPrefix)) {
                    summary = i;
                }
            }

            System.out.println();
            if (summary < 0) {
                System.out.println(
                        "Reactor summary was not found in the last "
                                + tailLines
                                + " lines. See "
                                + logFile
                                + " for the complete output.");
                return;
            }

            int start;
            int end;

            // Mirror the previous Bash implementation by selecting the complete
            // range enclosed by Maven's final four separator lines. This includes
            // the reactor summary, BUILD SUCCESS, total time, and finished-at line.
            if (separators.size() >= 4) {
                start = separators.get(separators.size() - 4);
                end = separators.get(separators.size() - 1) + 1;
            } else {
                start = summary;
                for (int separator : separators) {
                    if (separator <= summary) {
                        start = separator;
                    } else {
                        break;
                    }
                }

                end = lines.size();
                for (int i = separators.size() - 1; i >= 0; i--) {
                    int separator = separators.get(i);
                    if (separator > summary) {
                        end = separator + 1;
                        break;
                    }
                }
            }

            lines.subList(start, end).stream()
                    .map(line -> line.replaceFirst("^\\[INFO\\] ", ""))
                    .forEach(System.out::println);
        }
    }

    private static IOException combine(IOException first, IOException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static void terminateProcessTree(
            Process process,
            Set<ProcessHandle> knownDescendants) {
        process.descendants().forEach(knownDescendants::add);

        List<ProcessHandle> descendants = new ArrayList<>(knownDescendants);
        descendants.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());

        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroy();
            }
        }
        if (process.isAlive()) {
            process.destroy();
        }

        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static void destroyKnownDescendants(Set<ProcessHandle> knownDescendants) {
        for (ProcessHandle descendant : knownDescendants) {
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static String quoteForDisplay(String argument) {
        if (argument.isEmpty()) {
            return "\"\"";
        }
        if (argument.chars().noneMatch(Character::isWhitespace)) {
            return argument;
        }
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  java MavenBuildRunner.java [runner options] [--] [Maven arguments]

                Runner options:
                  --log-file <path>    Complete Maven output file
                                       Default: build.log
                  --tail-lines <count> Number of output lines retained and printed on failure
                                       Default: 2000
                  --heading <text>     Optional text printed before the Maven command
                                       Default: not shown
                  -h, --help           Show this help

                Maven arguments:
                  When omitted: clean verify -B -T 1.5C -U
                  Use -- to clearly separate runner options from Maven arguments.

                Examples:
                  java MavenBuildRunner.java
                  java MavenBuildRunner.java --log-file target/build.log
                  java MavenBuildRunner.java --heading "Building all projects"
                  java MavenBuildRunner.java --tail-lines 3000 -- clean verify -B -T 2C
                """);
    }

    private static int run(String[] args) throws Exception {
        Configuration configuration = Configuration.parse(args);
        if (configuration.showHelp()) {
            printUsage();
            return 0;
        }

        System.out.println("Using " + MAVEN_EXECUTABLE.description());

        int versionExitCode = new MavenVersionCommandProcess().execute();
        if (versionExitCode != 0) {
            return versionExitCode;
        }

        System.out.println();
        return new MavenBuildCommandProcess(configuration).execute();
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run(args);
        } catch (Exception e) {
            System.err.println("Maven build runner failed: " + e.getMessage());
            e.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}
