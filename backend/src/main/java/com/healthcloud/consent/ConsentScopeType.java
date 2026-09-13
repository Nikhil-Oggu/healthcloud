package com.healthcloud.consent;

/**
 * How broadly a consent directive applies (source-of-truth §22.1). This also encodes the specificity the
 * future policy evaluator resolves conflicts by (§22.5): PROVIDER (most specific) &gt; CARE_TEAM &gt;
 * ORGANIZATION (least specific). A PROVIDER-scoped directive names the provider in {@code scopeRefId}.
 */
public enum ConsentScopeType {
    PROVIDER,
    CARE_TEAM,
    ORGANIZATION
}
