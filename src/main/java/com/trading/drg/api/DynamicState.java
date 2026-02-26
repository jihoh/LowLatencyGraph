package com.trading.drg.api;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Interface for components that can serialize their internal dynamic state
 * directly to a StringBuilder.
 */
public interface DynamicState {
    /**
     * Serializes internal state using reflection to automatically append standard
     * JSON key-value pairs separated by commas without surrounding braces.
     */
    default void serializeDynamicState(StringBuilder sb) {
        boolean first = true;
        for (Field field : this.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(this);
                if (!first) {
                    sb.append(",");
                }
                sb.append("\"").append(field.getName()).append("\":");

                if (value == null) {
                    sb.append("null");
                } else if (value instanceof Number || value instanceof Boolean) {
                    sb.append(value);
                } else {
                    sb.append("\"").append(value.toString().replace("\"", "\\\"")).append("\"");
                }
                first = false;
            } catch (IllegalAccessException e) {
                // Ignore fields that cannot be accessed
            }
        }
    }
}
