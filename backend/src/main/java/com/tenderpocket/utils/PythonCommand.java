package com.tenderpocket.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Finds the command that starts Python 3 on this machine, for the helper scripts in backend/scripts.
 *
 * <p>Linux and the Docker image have {@code python3}. A normal Windows install has {@code python}
 * and the {@code py} launcher but no {@code python3} (or only the Microsoft Store stub, which does
 * not run). A configured command (property {@code python.command}, env {@code PYTHON_COMMAND})
 * always wins; otherwise the first candidate that answers {@code --version} with Python 3 is used.
 */
public final class PythonCommand {

    private static volatile List<String> cached;

    private PythonCommand() {}

    /** The command as ProcessBuilder arguments, e.g. ["python3"] or ["py", "-3"]. Detected once. */
    public static List<String> resolve(String configured) {
        List<String> c = cached;
        if (c == null) {
            synchronized (PythonCommand.class) {
                c = cached;
                if (c == null) {
                    c = resolve(configured, System.getProperty("os.name", ""), PythonCommand::runsPython3);
                    cached = c;
                    System.out.println("[Python] Using " + String.join(" ", c) + " for helper scripts");
                }
            }
        }
        return c;
    }

    static List<String> resolve(String configured, String osName, Predicate<List<String>> works) {
        if (configured != null && !configured.isBlank()) {
            return List.of(configured.trim().split("\\s+"));
        }
        List<List<String>> candidates = candidates(osName);
        for (List<String> candidate : candidates) {
            if (works.test(candidate)) return candidate;
        }
        // Nothing answered: keep the platform's usual name so the error message names it.
        return candidates.get(0);
    }

    static List<List<String>> candidates(String osName) {
        List<List<String>> list = new ArrayList<>();
        if (osName.toLowerCase(Locale.ROOT).contains("win")) {
            list.add(List.of("python"));
            list.add(List.of("py", "-3"));
            list.add(List.of("python3"));
        } else {
            list.add(List.of("python3"));
            list.add(List.of("python"));
        }
        return list;
    }

    private static boolean runsPython3(List<String> command) {
        try {
            List<String> args = new ArrayList<>(command);
            args.add("--version");
            Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && out.startsWith("Python 3");
        } catch (Exception e) {
            return false;
        }
    }
}
