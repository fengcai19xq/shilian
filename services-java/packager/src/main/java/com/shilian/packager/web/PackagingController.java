package com.shilian.packager.web;

import com.shilian.packager.core.PackagingExecutor;
import com.shilian.packager.model.Manifest;
import com.shilian.packager.model.PackageResult;
import com.shilian.packager.storage.Storage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 成册 HTTP 入口：manifest 进，产物 uri 出。只落存储，不对外发送。 */
@RestController
public class PackagingController {

    private final Storage storage;

    public PackagingController(Storage storage) {
        this.storage = storage;
    }

    @PostMapping("/v1/packages")
    public PackageResult execute(@RequestBody Manifest manifest) {
        return PackagingExecutor.execute(manifest, storage);
    }
}
