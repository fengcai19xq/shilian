package com.shilian.matcher.config;

public record MatcherConfig(ScoringConfig scoring, ParserConfig parser) {

    public MatcherConfig {
        scoring = scoring == null ? new ScoringConfig() : scoring;
        parser = parser == null ? new ParserConfig() : parser;
    }

    public MatcherConfig() {
        this(null, null);
    }
}
