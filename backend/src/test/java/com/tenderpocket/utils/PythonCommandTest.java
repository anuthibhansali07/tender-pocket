package com.tenderpocket.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PythonCommandTest {

    @Test
    void configuredCommandWinsWithoutProbing() {
        List<List<String>> probed = new ArrayList<>();
        List<String> cmd = PythonCommand.resolve("  py -3.11 ", "Windows 11", c -> { probed.add(c); return true; });
        assertEquals(List.of("py", "-3.11"), cmd);
        assertEquals(0, probed.size());
    }

    @Test
    void windowsWithoutPython3FindsPython() {
        // This machine: python3 is not installed, python (3.13) and the py launcher are.
        List<String> cmd = PythonCommand.resolve("", "Windows 11", c -> !c.get(0).equals("python3"));
        assertEquals(List.of("python"), cmd);
    }

    @Test
    void windowsFallsBackToPyLauncher() {
        List<String> cmd = PythonCommand.resolve(null, "Windows 11", c -> c.get(0).equals("py"));
        assertEquals(List.of("py", "-3"), cmd);
    }

    @Test
    void linuxAndDockerKeepPython3() {
        List<List<String>> probed = new ArrayList<>();
        List<String> cmd = PythonCommand.resolve(null, "Linux", c -> { probed.add(c); return true; });
        assertEquals(List.of("python3"), cmd);
        assertEquals(List.of(List.of("python3")), probed, "stops at the first command that works");
    }

    @Test
    void nothingWorksKeepsThePlatformDefaultSoTheErrorNamesIt() {
        assertEquals(List.of("python"), PythonCommand.resolve(null, "Windows 11", c -> false));
        assertEquals(List.of("python3"), PythonCommand.resolve(null, "Linux", c -> false));
    }
}
