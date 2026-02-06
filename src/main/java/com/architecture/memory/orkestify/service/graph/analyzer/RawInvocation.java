package com.architecture.memory.orkestify.service.graph.analyzer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A raw method invocation as found in source code before resolution.
 *
 * Example: this.paymentService.processPayment(order)
 *   - targetFieldName = "paymentService" (the field on which the method is called)
 *   - declaredTypeName = "IPaymentService" (the compile-time type of the field)
 *   - methodName = "processPayment"
 *   - signature = "processPayment(Order)"
 *
 * Resolution in Pass 2 will map declaredTypeName -> concrete implementation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawInvocation {
    // The field name used in the call (e.g., "paymentService")
    private String targetFieldName;

    // The declared type (may be interface): simple name and qualified name
    private String declaredTypeSimple;
    private String declaredTypeQualified;

    // The method being called
    private String methodName;
    private String signature;

    private int lineStart;
    private int lineEnd;

    // Whether this is a call on 'this' (same class method call)
    private boolean selfCall;
}
