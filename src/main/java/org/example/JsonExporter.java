package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * 최종 출력(JSON)은 "명령어 노드(오프셋/hex/mnemonic/operands)"와
 * "엣지 집합(CFG/EX/DFG/CDG/DDG)"만 포함. IR 텍스트는 일절 출력하지 않는다.
 */
public class JsonExporter {

    public static void export(String internalClassName, String methodName, String methodDesc,
                              BcelBytecodeCFG.Graph g, WalaIRProjector.Flow f, Path out, String analyzeMode) throws IOException {

        ObjectMapper om = new ObjectMapper();
        ObjectNode root = om.createObjectNode();
        root.put("method", internalClassName.replace('/', '.') + "." + methodName + methodDesc);

        // 1. nodes 만들기

        ArrayNode nodes = om.createArrayNode();
        for (Map.Entry<Integer, InstructionInfo> e : g.nodes.entrySet()) {
            InstructionInfo info = e.getValue();
            ObjectNode n = om.createObjectNode();
            n.put("offset", info.offset);
            n.put("hex", info.hexBytes);
            n.put("mnemonic", info.mnemonic);
            n.put("operands", info.operands);
            nodes.add(n);
        }
        root.set("nodes", nodes);

        // 2. edges 만들기

        ObjectNode edges = om.createObjectNode();
        if (!"DEPENDENCY_ONLY".equals(analyzeMode)) {
            addIfNotEmpty(edges, "cfg", pairs(om, g.cfgEdges));
            addIfNotEmpty(edges, "ex", pairs(om, g.exEdges));
            addIfNotEmpty(edges, "dfg", pairs(om, f.dfg));
        }
        if (!"FLOW_ONLY".equals(analyzeMode)) {
            addIfNotEmpty(edges, "cdg", pairs(om, f.cdg));
            addIfNotEmpty(edges, "ddg", pairs(om, f.ddg));
        }
        root.set("edges", edges);

        // 3. 파일 출력

        om.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), root);
    }

    private static void addIfNotEmpty(ObjectNode parent, String fieldName, ArrayNode arr) {
        if (arr.size() > 0) {
            parent.set(fieldName, arr);
        }
    }

    private static ArrayNode pairs(ObjectMapper om, Map<Integer, Set<Integer>> adj) {
        ArrayNode arr = om.createArrayNode();
        for (var e : adj.entrySet()) {
            int src = e.getKey();
            for (int dst : e.getValue()) {
                ObjectNode p = om.createObjectNode();
                p.put("src", src); p.put("dst", dst);
                arr.add(p);
            }
        }
        return arr;
    }
}
