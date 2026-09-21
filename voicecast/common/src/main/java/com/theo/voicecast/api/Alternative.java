package com.theo.voicecast.api;

/**
 * One runner-up candidate of an adjudication (semantic contract v2): the
 * next-best vocabulary entries behind the winning decision, ordered by the
 * fusion tiers and then by score, at most {@code top-k <= 3}.
 */
public record Alternative(String spellId, String pronId, float score) {
}
