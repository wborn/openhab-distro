import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MavenBuild {

    static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    static final String BUILD_LOG = "build.log";

    static class Command {
        final String command;

        Command(String command) {
            this.command = command;
        }

        String getCommandForCurrentOS() {
            if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
                return "cmd.exe /c " + command;
            } else {
                return command;
            }
        }

        @Override
        public String toString() {
            return command;
        }
    }

    static class MaxCapacityList<E> extends ArrayList<E> {

        final int maxCapacity;

        public MaxCapacityList(int maxCapacity) {
            super(maxCapacity);
            this.maxCapacity = maxCapacity;
        }

        @Override
        public boolean add(E e) {
            if (size() > maxCapacity) {
                remove(0);
            }
            return super.add(e);
        }
    }

    static class CommandProcess {

        final ProcessBuilder builder = new ProcessBuilder();
        final Command command;

        CommandProcess(Command command) {
            this.command = command;
            builder.command(command.getCommandForCurrentOS().split(" "));
            builder.directory(new File(System.getProperty("user.dir")));
            builder.environment().putAll(System.getenv());
            builder.redirectErrorStream(true);
        }

        void execute() throws Exception {
            Process process = builder.start();
            handleInputStream(process.getInputStream()).get();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                handleError();
                System.exit(exitCode);
            }

            handleSuccess();
        }

        Future<?> handleInputStream(InputStream inputStream) {
            return CompletableFuture.completedFuture(null);
        }

        void handleError() {
        }

        void handleSuccess() {
        }
    }

    static class MavenVersionCommandProcess extends CommandProcess {
        MavenVersionCommandProcess() {
            super(new Command("mvn -v"));
            builder.inheritIO();
        }
    }

    static class MavenBuildCommandProcess extends CommandProcess {

        final MaxCapacityList<String> lastLines = new MaxCapacityList<>(2000);
        final Pattern pattern = Pattern.compile("^\\[INFO\\] Building (?<name>.+) \\[(?<index>\\d+)/(?<size>\\d+)\\]$");

        MavenBuildCommandProcess() {
            super(new Command("mvn clean verify -B -T 1.5C -U"));
        }

        @Override
        void execute() throws Exception {
            List.of("Building all projects", "", "+ " + command, "").stream().forEach(System.out::println);
            super.execute();
        }

        void printProgress(String line) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                String index = matcher.group("index");
                String size = matcher.group("size");
                String name = matcher.group("name");
                String padding = " ".repeat(size.length() - index.length());
                System.out.println(String.format("%s%s/%s| %s", padding, index, size, name));
            }
        }

        @Override
        Future<?> handleInputStream(InputStream inputStream) {
            return EXECUTOR.submit(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                        BufferedWriter writer = Files.newBufferedWriter(Path.of(BUILD_LOG), StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        printProgress(line);
                        writer.append(line + System.lineSeparator());
                        lastLines.add(line);
                    }
                } catch (IOException e) {
                }
            });
        }

        @Override
        void handleError() {
            System.out.println();
            lastLines.forEach(System.out::println);
        }

        @Override
        void handleSuccess() {
            String separatorPrefix = "[INFO] " + "-".repeat(70);
            String summaryPrefix = "[INFO] Reactor Summary";

            int start = 0;
            int end = lastLines.size();

            for (int i = 0; i < lastLines.size(); i++) {
                String line = lastLines.get(i);
                if (line.startsWith(summaryPrefix)) {
                    start = i - 1;
                } else if (line.startsWith(separatorPrefix)) {
                    end = i + 1;
                }
            }

            System.out.println();
            lastLines.subList(start, end).stream() //
                    .map(s -> s.replaceFirst("\\[INFO\\] ", "")) //
                    .forEach(System.out::println);
        }

    }

    public static void main(String[] args) throws Exception {
        new MavenVersionCommandProcess().execute();
        System.out.println();
        new MavenBuildCommandProcess().execute();
        System.exit(0);
    }
}
