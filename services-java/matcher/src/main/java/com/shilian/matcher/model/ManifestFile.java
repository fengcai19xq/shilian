package com.shilian.matcher.model;

import java.util.List;

public record ManifestFile(String uri, List<Integer> pages, int order) {
}
