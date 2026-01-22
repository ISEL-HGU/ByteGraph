package org.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Diagnosis {
    private final Set<String> packagesToUnblock = new HashSet<>();
    private final Set<String> missingLibraries = new HashSet<>();
    private final Map<String, Set<String>> classToMissingLibMap = new HashMap<>();
    private final Path exclusionsPath;

    public Diagnosis(Path exclusionsPath) {
        this.exclusionsPath = exclusionsPath;
    }

    public void analyzeError(String errorMessage, String currentClassName) {
        try {
            // 1. 에러 메시지에서 클래스 경로 추출
            String foundClass = extractClassName(errorMessage);

            // 자기 자신인 경우 즉시 종료 (리포트에 추가 안 함)
            if (foundClass == null || foundClass.equals(currentClassName)) return;

            // 2. 상위 패키지 중 exclusions.txt에 걸리는 게 있는지 확인
            String matchedExclusion = findExcludedPackage(foundClass.replace(".", "/"));
            if (matchedExclusion != null) {
                packagesToUnblock.add(matchedExclusion);
            } else {
                missingLibraries.add(foundClass);
                classToMissingLibMap.computeIfAbsent(currentClassName, k -> new HashSet<>()).add(foundClass);
            }
        } catch (Exception ignored) {}
    }

    private String extractClassName(String msg) {
        if (msg == null) return null;

        Pattern classPattern = Pattern.compile("L([a-zA-Z0-9/$_]+)(?=[;>\\s]|$)");
        Matcher matcher = classPattern.matcher(msg);

        String found = null;
        while (matcher.find()) {
            found = matcher.group(1);
        }

        if (found != null) {
            return found.replace("/", ".");
        }
        return null;
    }

    public void addMissingLibrary(String missingClassName, String currentClassName) {
        if (missingClassName == null || missingClassName.equals("java.lang.Object")) return;

        String missingPath = missingClassName.replace('.', '/');
        String currentPath = currentClassName.replace('.', '/');
        if (missingPath.equals(currentPath)) return;

        // 상위 패키지 경로 중 하나라도 exclusions에 있는지 확인
        String excludedPackage = findExcludedPackage(missingPath);

        if (excludedPackage != null) {
            packagesToUnblock.add(excludedPackage);
        } else {
            String libName = missingPath.replace('/', '.');
            missingLibraries.add(libName);
            classToMissingLibMap.computeIfAbsent(currentClassName, k -> new HashSet<>()).add(libName);

        }

    }

    private String findExcludedPackage(String classPath) {
        try {
            List<String> exclusions = Files.readAllLines(exclusionsPath);
            for (String line : exclusions) {
                String pattern = line.trim();
                if (pattern.startsWith("#") || pattern.isEmpty()) continue;

                String cleanPattern = pattern.replace("\\", "").replace(".*", "");

                if (classPath.startsWith(cleanPattern)) {
                    return pattern;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    public void clear() {
        this.packagesToUnblock.clear();
        this.missingLibraries.clear();
        this.classToMissingLibMap.clear();
    }

    public Set<String> getMissingLibraries() {
        return missingLibraries;
    }

    public Map<String, Set<String>> getClassToMissingLibMap() {
        return classToMissingLibMap;
    }

    public boolean hasSuggestions() {
        return !packagesToUnblock.isEmpty() || !missingLibraries.isEmpty();
    }

    public Set<String> getPackagesToUnblock() { return packagesToUnblock; }

    public void printReport() {
        System.out.println("\n" + "=".repeat(20) + " DIAGNOSIS REPORT " + "=".repeat(20));

        if (!packagesToUnblock.isEmpty()) {
            System.out.println("[Configuration Issue]");
            System.out.println("  - The following packages are blocked by 'exclusions.txt':");
            System.out.println("    " + packagesToUnblock);
            System.out.println("  - Action: The program will automatically unblock them in memory for Pass 2.");
        }

        if (!missingLibraries.isEmpty()) {
            System.out.println("\n[Library Dependency Issue]");
            System.out.println("  - The following classes/dependencies are missing:");
            for (String lib : missingLibraries) {
                System.out.println("    * " + lib);
            }
            System.out.println("  - Action: Please add the required JAR files (e.g., JavaFX SDK) to your classpath.");

            if (missingLibraries.toString().toLowerCase().contains("javafx")) {
                System.out.println("  - Note: JavaFX libraries (openjfx) are required for these classes.");
            }
        }

        if (packagesToUnblock.isEmpty() && missingLibraries.isEmpty()) {
            System.out.println("No specific library or exclusion issues identified.");
        }

        System.out.println("=".repeat(58) + "\n");
    }
}