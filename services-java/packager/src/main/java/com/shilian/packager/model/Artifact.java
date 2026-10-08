package com.shilian.packager.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** 单个产物的落地结果。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Artifact(OutputKind kind, String filename, String uri, long size) {}
