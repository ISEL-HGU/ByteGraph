package org.example;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -jar bytegraph.jar <class_or_dir>");
            System.exit(1);
        }
        // 분석 모드 결정 (기본값: SEMANTIC - 변수 흐름 강조)
        String mode = (args.length > 1) ? args[15].toUpperCase() : "SEMANTIC";
        System.out.println(">>> Analysis Mode: " + mode);

        Path input = Paths.get(args[0]).toAbsolutePath();
        // 1) 분석 대상 클래스 파일 수집
        List<Path> classFiles = collectClassFiles(input);
        String appClassPath = Files.isDirectory(input) ? input.toString() : input.getParent().toString();

        // 2) WALA 세션 초기화
        System.out.println(">>> [1/2] Initializing WALA Global Session (this may take a while)...");
        WalaSession session = WalaSession.init(appClassPath);
        System.out.println(">>> WALA Session initialized successfully.\n");

        // 3) 결과 폴더 생성
        Path outDir = Paths.get("out");
        Files.createDirectories(outDir);

        BcelBytecodeCFG bcel = new BcelBytecodeCFG();
        WalaIRProjector projector = new WalaIRProjector();
        ObjectMapper om = new ObjectMapper();

        int totalClasses = classFiles.size();
        int ok = 0, fail = 0;

        System.out.println(">>> [2/2] Starting analysis for " + totalClasses + " classes...");

        for (int i = 0; i < totalClasses; i++) {
            Path classFile = classFiles.get(i);
            String fileName = classFile.getFileName().toString();
            System.out.printf("[%d/%d] Processing: %s ... ", (i + 1), totalClasses, fileName);

            try {
                BcelClassIntrospector.ClassScan scan = BcelClassIntrospector.scanClassFile(classFile.toString());
                var root = om.createObjectNode();
                root.put("class", scan.internalName.replace('/', '.'));
                var methodsArr = om.createArrayNode();

                for (var ms : scan.methods) {
                    // BCEL 분석 (물리적 CFG)
                    BcelBytecodeCFG.Graph instrCFG = bcel.build(classFile.toString(), ms.name, ms.desc, mode);

                    // WALA 분석 (세션 주입)
                    WalaIRProjector.Flow flow = projector.analyze(session, scan.internalName, ms.name, ms.desc, instrCFG);

                    // JSON 노드 구성
                    var mnode = om.createObjectNode();
                    mnode.put("method", ms.name + ms.desc);

                    var nodes = om.createArrayNode();
                    instrCFG.nodes.forEach((off, info) -> {
                        var n = om.createObjectNode();
                        n.put("offset", info.offset);
                        n.put("hex", info.hexBytes);
                        n.put("mnemonic", info.mnemonic);
                        n.put("operands", info.operands);
                        nodes.add(n);
                    });
                    mnode.set("nodes", nodes);

                    var edges = om.createObjectNode();
                    edges.set("cfg", pairs(om, instrCFG.cfgEdges));
                    edges.set("ex", pairs(om, instrCFG.exEdges));
                    edges.set("dfg", pairs(om, flow.dfg));
                    edges.set("cdg", pairs(om, flow.cdg));
                    edges.set("ddg", pairs(om, flow.ddg));
                    mnode.set("edges", edges);
                    methodsArr.add(mnode);
                }

                root.set("methods", methodsArr);
                String outName = scan.internalName.replace('/', '.') + ".json";
                om.writerWithDefaultPrettyPrinter().writeValue(outDir.resolve(outName).toFile(), root);

                System.out.println("SUCCESS");
                ok++;
            } catch (Exception ex) {
                System.out.println("FAILED (" + ex.getMessage() + ")");
                fail++;
            }
        }

        System.out.println("\n========================================");
        System.out.printf("Analysis Finished: Total=%d, Success=%d, Fail=%d%n", totalClasses, ok, fail);
        System.out.println("========================================");
    }

    private static List<Path> collectClassFiles(Path input) throws IOException {
        List<Path> list = new ArrayList<>();
        if (Files.isDirectory(input)) {
            try (var stream = Files.walk(input)) {
                stream.filter(p -> p.toString().endsWith(".class")).forEach(list::add);
            }
        } else if (input.toString().endsWith(".class")) {
            list.add(input);
        }
        return list;
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode pairs(ObjectMapper om, Map<Integer, Set<Integer>> adj) {
        var arr = om.createArrayNode();
        for (var e : adj.entrySet()) {
            for (int dst : e.getValue()) {
                var p = om.createObjectNode();
                p.put("src", e.getKey()); p.put("dst", dst);
                arr.add(p);
            }
        }
        return arr;
    }
}