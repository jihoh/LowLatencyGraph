package com.trading.drg.demo;

import com.trading.drg.CoreGraph;
import com.trading.drg.engine.TopologicalOrder;

public class InspectEdges {
    public static void main(String[] args) {
        CoreGraph graph = new CoreGraph("src/main/resources/bond_pricer.json");
        TopologicalOrder topo = graph.getEngine().topology();

        System.out.println("Total nodes: " + topo.nodeCount());
        for (int i = 0; i < topo.nodeCount(); i++) {
            String name = topo.node(i).name();
            int start = topo.childrenStart(i);
            int end = topo.childrenEnd(i);
            for (int c = start; c < end; c++) {
                int childIdx = topo.childAt(c);
                String childName = topo.node(childIdx).name();
                if (childIdx == 0 || childIdx == 1 || childName.contains("timer")) {
                    System.out.printf("WARNING! Node %d (%s) has child %d (%s)%n", i, name, childIdx, childName);
                }
            }
        }

        System.out.println("\nParent counts:");
        for (int i = 0; i < topo.nodeCount(); i++) {
            if (topo.node(i).name().contains("timer")) {
                System.out.printf("Node %d (%s): children count = %d%n", i, topo.node(i).name(), topo.childCount(i));
            }
        }
    }
}
