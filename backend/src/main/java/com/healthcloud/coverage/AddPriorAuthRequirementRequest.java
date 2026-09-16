package com.healthcloud.coverage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload to mark a procedure as requiring prior authorization under a coverage plan. The {@code procedureCode}
 * is resolved and validated against the global medical code catalog (CPT/HCPCS); an unknown or non-procedure code
 * is a 400. The plan and tenant come from the path/context, never the client.
 */
public record AddPriorAuthRequirementRequest(@NotBlank @Size(max = 16) String procedureCode) {
}
