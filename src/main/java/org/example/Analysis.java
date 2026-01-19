package org.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class Analysis {
    private final String dfgMode;
    private final String analyzeMode;
    private final Diagnosis diagnosis;

    public Analysis(String dfgMode, String outputMode, Diagnosis diagnosis) {
        this.dfgMode = dfgMode;
        this.analyzeMode = outputMode;
        this.diagnosis = diagnosis;
    }

    public int run(WalaSession session, List<Path> files, Set<Path> failedFiles) {
        BcelBytecodeCFG bcel = new BcelBytecodeCFG();
        WalaIRProjector projector = new WalaIRProjector();

        int successCount = 0;
        int failCount = 0;
        int interfaceCount = 0;

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            boolean classHasError = false;
            boolean hasNormalMethodSuccess = false;
            List<String> failedMethodNames = new ArrayList<>();

            try {
                BcelClassIntrospector.ClassScan scan = BcelClassIntrospector.scanClassFile(file.toString());
                String className = scan.internalName.replace('/', '.');

                try {
                    if (projector.isInterfaceClass(session, scan.internalName) || scan.methods.isEmpty()) {
                        System.out.println("[RESULT] INTERFACE : " + className);
                        interfaceCount++;
                        continue;
                    }
                } catch (Exception e) {
                    // WALA가 부모 클래스를 못 찾아서 로드에 실패하면 여기서 바로 진단하고 다음 파일로!
                    diagnosis.addMissingLibrary(scan.superName, className);
                    diagnosis.analyzeError(e.getMessage(), className);
                    failCount++;
                    failedFiles.add(file);
                    System.out.println("[RESULT] FAIL      : " + className + " ( Class Hierarchy Incomplete )");
                    continue;
                }

                // 3. 의존성 문제가 없다면 메서드 분석 루프 시작
                for (var ms : scan.methods) {
                    try {
                        if (projector.isAbstractMethod(session, scan.internalName, ms.name, ms.desc)) {
                            continue;
                        }

                        // 2. 일반 메서드인 경우에만 CFG 빌드 및 분석 진행
                        BcelBytecodeCFG.Graph instrCFG = bcel.build(file.toString(), ms.name, ms.desc, dfgMode);
                        WalaIRProjector.Flow flow = projector.analyze(session, scan.internalName, ms.name, ms.desc, instrCFG, analyzeMode);

                        if (flow != null) {
                            Path outDir = Paths.get("out");
                            Files.createDirectories(outDir);
                            String qName = scan.internalName.replace('/', '.') + "." + ms.name;
                            String safeFileName = qName.replace("<", "").replace(">", "") + ".json";
                            JsonExporter.export(scan.internalName, ms.name, ms.desc, instrCFG, flow, outDir.resolve(safeFileName), analyzeMode);
                            hasNormalMethodSuccess = true;
                        }
                    } catch (Exception e) {
                        failedMethodNames.add(ms.name);
                        diagnosis.analyzeError(e.getMessage(), className);
                        if (e.getMessage().contains("Class not found: L" + scan.internalName)) {
                            diagnosis.addMissingLibrary(scan.superName.replace('/', '.'), scan.internalName);
                        }
                        classHasError = true;
                    }
                }

                // 최종 분류 로직
                if (classHasError) {
                    failCount++;
                    failedFiles.add(file);
                    String methods = String.join(", ", failedMethodNames);
                    System.out.println("[RESULT] FAIL      : " + className+ " ( " + methods + " )");
                } else if (hasNormalMethodSuccess) {
                    successCount++;
                    System.out.println("[RESULT] SUCCESS   : " + className);
                }

            } catch (Exception ex) {
                // 스캔 실패
                failCount++;
                failedFiles.add(file);
                String backupClassName = fileName.endsWith(".class")
                        ? fileName.substring(0, fileName.length() - 6)
                        : fileName;
                diagnosis.analyzeError(ex.getMessage(), backupClassName);
                System.out.println("[RESULT] FAIL      : " + file.getFileName() + " ( Error: " + ex.getMessage() + " )");
            }
        }

        System.out.println("\n" + "=".repeat(40));
        System.out.println(">>> Pass Finished Summary");
        System.out.println("  - Success   : " + successCount);
        System.out.println("  - Fail      : " + failCount);
        if (interfaceCount>0) System.out.println("  - Interface : " + interfaceCount);
        System.out.println("=".repeat(40));

        return successCount;
    }
}