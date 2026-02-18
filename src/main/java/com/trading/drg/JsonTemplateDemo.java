package com.trading.drg;

import com.trading.drg.api.ScalarValue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Demonstrates the usage of JSON Templates.
 */
public class JsonTemplateDemo {
    private static final Logger log = LogManager.getLogger(JsonTemplateDemo.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting JSON Template Demo...");

        // 1. Initialize CoreGraph with template-based JSON
        var graph = new CoreGraph("src/main/resources/json_template_demo.json");
        graph.start();

        // 2. Access nodes created from templates
        var aIn = graph.<ScalarValue>getNode("A.in");
        var aOut = graph.<ScalarValue>getNode("A.out");
        var bIn = graph.<ScalarValue>getNode("B.in");
        var bOut = graph.<ScalarValue>getNode("B.out");

        log.info("Initial Values: A.out={}, B.out={}", aOut.doubleValue(), bOut.doubleValue());

        // 3. Update A
        graph.publish("A.in", 10.0, true);
        Thread.sleep(100);
        log.info("After A=10: A.out={}, B.out={}", aOut.doubleValue(), bOut.doubleValue());

        // 4. Update B
        graph.publish("B.in", 20.0, true);
        Thread.sleep(100);
        log.info("After B=20: A.out={}, B.out={}", aOut.doubleValue(), bOut.doubleValue());

        graph.stop();
    }
}
