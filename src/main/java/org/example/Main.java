package org.example;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class Main {
     private static final Path EXCLUSIONS_PATH = Paths.get("exclusions.txt");

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        // 1. set options
        String targetPathStr = null;
        String dfgMode = "DATA_SEMANTIC";
        String analyzeMode = "ALL";
        List<String> extraLibs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-t":
                    if (i + 1 < args.length) targetPathStr = args[++i];
                    break;
                case "-d":
                    if (i + 1 < args.length) dfgMode = args[++i].toUpperCase();
                    break;
                case "-a":
                    if (i + 1 < args.length) analyzeMode = args[++i].toUpperCase();
                    break;
                case "-l":
                    if (i + 1 < args.length) extraLibs.add(args[++i]);
                    break;
                default:
                    if (targetPathStr == null && !args[i].startsWith("-")) {
                        targetPathStr = args[i];
                    }
                    break;
            }
        }

        if (targetPathStr == null) {
            printUsage();
            return;
        }

        if (!Set.of("ALL", "FLOW_ONLY", "DEPENDENCY_ONLY").contains(analyzeMode)) {
            System.err.println("Invalid graph mode option. Use ALL, FLOW_ONLY, or DEPENDENCY_ONLY.");
            return;
        }

        if (!Set.of("DATA_STACK", "DATA_SEMANTIC", "DATA_LOCAL", "WALA_ONLY").contains(dfgMode)) {
            System.err.println("Invalid graph mode option. Use DATA_STACK, DATA_SEMANTIC, DATA_LOCAL, or WALA_ONLY.");
            return;
        }

        Path targetPath = Paths.get(targetPathStr).toAbsolutePath();
        String appClassPath = Files.isDirectory(targetPath) ? targetPath.toString() : targetPath.getParent().toString();
        Diagnosis diagnosis = new Diagnosis(EXCLUSIONS_PATH);
        Analysis engine = new Analysis(dfgMode, analyzeMode, diagnosis);
        Set<Path> failedFiles = new LinkedHashSet<>();

        System.out.println("Analsis Class Path : "+targetPathStr);
        System.out.println("Analysis Mode : "+analyzeMode);
        System.out.println("DFG Mode : "+dfgMode);
        if (!extraLibs.isEmpty()) System.out.println("External Libraries : "+extraLibs);


        try {
            // [1차 시도] 기존 exclusions.txt 사용하여 빠르게 분석
            System.out.println(">>> [Pass 1] Starting fast analysis with exclusions...");
            WalaSession session1 = null;
            int retryCount = 0;
            int MAX_RETRIES = 10;

            while (session1 == null && retryCount < MAX_RETRIES) {
                try {
                    session1 = WalaSession.init(appClassPath, diagnosis.getPackagesToUnblock(), extraLibs);
                } catch (Exception e) {
                    retryCount++;
                    diagnosis.analyzeError(e.getMessage(), "SystemInit");
                    if (diagnosis.hasSuggestions()) {
                        System.out.println(">>> [Retry " + retryCount + "] Found issues: " + diagnosis.getPackagesToUnblock());
                        System.out.println(">>> Updating scope and retrying initialization...");
                        diagnosis.printReport();
                    } else {
                        System.err.println(">>> [Critical] Failed to initialize session even after healing.");
                        diagnosis.printReport();
                        throw e;
                    }
                }
            }

            if (session1 != null) {
                List<Path> filesToProcess = Files.isDirectory(targetPath)
                        ? Files.walk(targetPath).filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList())
                        : List.of(targetPath);

                engine.run(session1, filesToProcess, failedFiles);
            }

            // [2차 시도 - Healing] 실패한 파일 재시도
            if (!failedFiles.isEmpty()) {
                System.out.println("\n>>> [Pass 2] Healing session starting...");
                // 1차 시도 결과 스냅샷 저장
                Set<String> pass1Exclusions = new HashSet<>(diagnosis.getPackagesToUnblock());
                Set<String> pass1Missing = new HashSet<>(diagnosis.getMissingLibraries());


                // 1차 시도에서 수집된 에러를 바탕으로 해결책이 있는지 확인
                if (diagnosis.hasSuggestions()) {

                    // 차단 해제가 필요한 패키지가 있다면 2차 시도 진행
                    if (!diagnosis.getPackagesToUnblock().isEmpty() || !diagnosis.getMissingLibraries().isEmpty()) {
                        System.out.println(">>> Retrying with dynamic unblocking for: " + diagnosis.getPackagesToUnblock());

                        diagnosis.clear();
                        WalaSession healingSession = WalaSession.init(appClassPath, pass1Exclusions, extraLibs);

                        // 2차 시도
                        Set<Path> pass2Failed = new HashSet<>();
                        engine.run(healingSession, new ArrayList<>(failedFiles), pass2Failed);
                        System.out.println("\n" + "=".repeat(20) + " FINAL HEALING REPORT " + "=".repeat(20));

                        // 1. 해결된 항목 (Pass 1에는 있었으나 Pass 2에서는 발생하지 않음)
                        Set<String> resolvedExclusions = new HashSet<>(pass1Exclusions);
                        resolvedExclusions.removeAll(diagnosis.getPackagesToUnblock());

                        if (!resolvedExclusions.isEmpty()) {
                            System.out.println("[RESOLVED] These exclusions no longer cause errors:");
                            resolvedExclusions.forEach(s -> System.out.println(" + " + s));
                        }

                        // 2. 여전히 해결되지 않은 항목 (Pass 2에서도 다시 수집됨)
                        if (!diagnosis.getPackagesToUnblock().isEmpty() || !diagnosis.getMissingLibraries().isEmpty()) {
                            System.out.println("\n[UNRESOLVED] Still causing issues after healing session:");

                            // 클래스 -> 라이브러리 매칭 출력
                            if (!diagnosis.getClassToMissingLibMap().isEmpty()) {
                                System.out.println(" --- Missing Dependency Mapping ---");
                                diagnosis.getClassToMissingLibMap().forEach((targetClass, libs) -> {
                                    System.out.println(" * Target: " + targetClass + " -> Needs: " + libs);
                                });
                            }

                            if (!diagnosis.getPackagesToUnblock().isEmpty()) {
                                System.out.println(" - Persistent Exclusions: " + diagnosis.getPackagesToUnblock());
                            }

                            // 3. 수동 해결 가이드 출력
                            System.out.println("\n" + "-".repeat(60));
                            System.out.println(">>> Manual Fix Guide");
                            System.out.println("-".repeat(60));
                            System.out.println("1) Library Issue: Add the JAR files identified in the mapping above to your classpath.");
                            System.out.println("2) Scope Exclusion Issue: Check whether there is a comment (#) in front of the package in 'exclusions.txt' and remove it if necessary.");
                            System.out.println("3) Environment Check: Ensure that 'rt.jar' and 'jce.jar' are available in the [root folder]/lib/ directory of this repository. Verify that these files have not been deleted.");

                            System.out.println("-".repeat(60));
                        }

                        if (pass2Failed.isEmpty()) {
                            System.out.println("\n>>> ALL FAILURES SUCCESSFULLY HEALED!");
                        }

                        System.out.println("=".repeat(60) + "\n");
                    }
                } else {
                    System.out.println(">>> No clear healing path found for remaining failures.");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printManualGuide() {
        System.out.println("\n" + "-".repeat(60));
        System.out.println(">>> Manual Fix Guide");
        System.out.println("-".repeat(60));
        System.out.println("1) Library Issue: Add missing JAR files to your classpath using -l option.");
        System.out.println("2) Environment Check: Ensure 'rt.jar' and 'jce.jar' are in [root]/lib/ folder.");
        System.out.println("-".repeat(60));
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar bytegraph.jar <appClassPath> [mode] [ddgOption]");
    }
}