package org.example;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.core.util.strings.Atom;
import com.ibm.wala.types.Selector;
import org.apache.bcel.classfile.Method;

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
            BcelClassIntrospector.ClassScan scan;

            // 1. class 찾기

            // 1-1. class scan : BCEL
            try {
                scan = BcelClassIntrospector.scanClassFile(file.toString());
            } catch (Exception ex) {
                failCount++;
                failedFiles.add(file);
                String backupClassName = fileName.endsWith(".class")
                        ? fileName.substring(0, fileName.length() - 6)
                        : fileName;
                diagnosis.analyzeError(ex.getMessage(), backupClassName);
                System.out.println("[RESULT] FAIL      : " + file.getFileName() + " ( Error: " + ex.getMessage() + " )");
                continue;
            }
            String className = scan.internalName.replace('/', '.');

            // 1-2. interface/abstract class 확인
            try {
                if (projector.isInterfaceClass(session, scan.internalName) || scan.methods.isEmpty()) {
                    System.out.println("[RESULT] INTERFACE : " + className);
                    interfaceCount++;
                    continue;
                }
            }

            // 1-3. class load 실패 진단
            catch (Exception e) {
                diagnosis.addMissingLibrary(scan.superName, className);
                diagnosis.analyzeError(e.getMessage(), className);
                failCount++;
                failedFiles.add(file);
                System.out.println("[RESULT] FAIL      : " + className + " ( Class Hierarchy Incomplete )");
                continue;
            }

            // 1-4. WALA class 찾기
            IClass walaClass = projector.getClassFromSession(session, scan.internalName);
            if (walaClass == null) {
                diagnosis.addMissingLibrary(scan.superName, className);
                diagnosis.analyzeError("Class not found: L" + scan.internalName, className);
                failCount++;
                failedFiles.add(file);
                System.out.println("[RESULT] FAIL : " + className + " ( Class Hierarchy Incomplete )");
                continue;
            }

            // 2. method 분석

            for (Method method : scan.methods) {
                try {
                    IMethod walaMethod = walaClass.getMethod(new Selector(
                            Atom.findOrCreateUnicodeAtom(method.getName()),
                            com.ibm.wala.types.Descriptor.findOrCreateUTF8(Language.JAVA, method.getSignature())
                    ));

                    if (walaMethod == null || walaMethod.isAbstract()) continue;

                    // 2-1. 일반 메서드일 경우 GRAPH 분석 진행
                    BcelBytecodeCFG.Graph instrCFG = bcel.build(scan.jClass, method, dfgMode);
                    WalaIRProjector.Flow flow = projector.analyze(
                            session, walaMethod, instrCFG, analyzeMode);

                    // 2-2. 결과 JSON file 출력
                    if (flow != null) {
                        Path outDir = Paths.get("out");
                        Files.createDirectories(outDir);
                        String qName = scan.internalName.replace('/', '.') + "." + method.getName();
                        String safeFileName = qName.replace("<", "").replace(">", "") + ".json";
                        JsonExporter.export(scan.internalName, method.getName(), method.getSignature(),
                                instrCFG, flow, outDir.resolve(safeFileName), analyzeMode);
                        hasNormalMethodSuccess = true;
                    }
                }

                // 2-3. class 실패 진단
                catch (Exception e) {
                    failedMethodNames.add(method.getName());
                    diagnosis.analyzeError(e.getMessage(), className);
                    if (e.getMessage().contains("Class not found: L" + scan.internalName)) {
                        diagnosis.addMissingLibrary(scan.superName.replace('/', '.'), scan.internalName);
                    }
                    classHasError = true;
                }
            }

            // 3. class 분석 성공/실패 출력

            if (classHasError) {
                failCount++;
                failedFiles.add(file);
                String methods = String.join(", ", failedMethodNames);
                System.out.println("[RESULT] FAIL      : " + className+ " ( " + methods + " )");
            } else if (hasNormalMethodSuccess) {
                successCount++;
                System.out.println("[RESULT] SUCCESS   : " + className);
            }
        }

        // 4. 최종 결과 출력

        System.out.println("\n" + "=".repeat(40));
        System.out.println(">>> Pass Finished Summary");
        System.out.println("  - Success   : " + successCount);
        System.out.println("  - Fail      : " + failCount);
        if (interfaceCount>0) System.out.println("  - Interface : " + interfaceCount);
        System.out.println("=".repeat(40));

        return successCount;
    }
}