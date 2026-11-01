package com.os.payment;

import com.amazonaws.services.lambda.runtime.Context;

/**
 * Minimal structured logging to CloudWatch. Mirrors the JsonLog helper in
 * os-management-lambda so the two functions' logs query the same way.
 */
final class JsonLog {

    private JsonLog() {
    }

    static void warn(Context context, String event, String messageId, String reason) {
        String line = String.format(
                "{\"level\":\"WARN\",\"event\":\"%s\",\"messageId\":\"%s\",\"reason\":\"%s\"}",
                escape(event), escape(messageId), escape(reason));

        if (context != null && context.getLogger() != null) {
            context.getLogger().log(line);
        } else {
            System.out.println(line);
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
