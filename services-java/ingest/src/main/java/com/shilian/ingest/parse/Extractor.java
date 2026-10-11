package com.shilian.ingest.parse;

import java.util.List;

interface Extractor {

    List<PageText> extract(byte[] content) throws Exception;
}
