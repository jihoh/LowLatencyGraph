package com.trading.drg.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Extracts source code of specific implementation classes for web UI export.
 */
public class SourceExtractor {

    /** Extracts class source excluding package and imports. */
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
