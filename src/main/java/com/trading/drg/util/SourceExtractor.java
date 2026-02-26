package com.trading.drg.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility for extracting the source code of specific methods from .java files.
 * Used for exporting core mathematical logic to the web UI.
 */
public class SourceExtractor {

    /**
     * Extracts the class source from the specified class's source file,
     * excluding package and import statements.
     * Searches globally in the expected src/main/java directory.
     * 
     * @param className the fully qualified class name (e.g.
     *                  "com.trading.drg.fn.finance.Ewma")
     * @return the raw string block representing the class source, or a fallback
     *         message
     *         if not found/error.
     */
    public static String extractClassSource(String className) {
        if (className == null || className.isEmpty()) {
            return "No underlying implementation class defined.";
        }

        try {
            Path p = Paths.get("src/main/java", className.replace('.', '/') + ".java");
            if (!Files.exists(p))
                return "Source file not found at: " + p.toString();
            String content = Files.readString(p);

            // Find the end of the last import statement
            int lastImportIndex = content.lastIndexOf("import ");
            if (lastImportIndex != -1) {
                int endOfImport = content.indexOf(";", lastImportIndex);
                if (endOfImport != -1) {
                    return content.substring(endOfImport + 1).trim();
                }
            }

            // Fallback: Find the end of the package statement
            int packageIndex = content.indexOf("package ");
            if (packageIndex != -1) {
                int endOfPackage = content.indexOf(";", packageIndex);
                if (endOfPackage != -1) {
                    return content.substring(endOfPackage + 1).trim();
                }
            }

            return content.trim();
        } catch (Exception e) {
            return "Error reading source: " + e.getMessage();
        }
    }
}
